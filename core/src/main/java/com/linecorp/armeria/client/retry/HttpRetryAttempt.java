/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.client.retry.AbstractRetryingClient.ARMERIA_RETRY_COUNT;
import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContextAwareEventLoop;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

/**
 * A single attempt for a {@link RetriedHttpRequest}.
 *
 *  <p>
 *   NOTE: All methods of {@link HttpRetryAttempt} must be invoked from the
 *   {@code retryEventLoop}.
 *  </p>
 */
final class HttpRetryAttempt {
    /**
     * Result of {@code State.PREPARING_AND_DECIDING} which contains the response of the attempt
     * and the response or cause to be delivered to the {@link RetryRule} for decision
     * processing the response until the point where the {@link RetryRule} can make a decision
     * (e.g., receiving headers, content, or trailers).
     */
    private static final class PreparationResult {
        /** The response of the attempt. */
        final HttpResponse res;

        /**
         * Response that is derived from {@link PreparationResult#res} which is delivered to
         * the {@link RetryRule} for decision. May be {@link PreparationResult#res} itself.
         * It is {@code null} iff {@link PreparationResult#causeToDecide} is not {@code null}.
         */
        @Nullable
        final HttpResponse resToDecide;

        /**
         * Exception that occurred while preparing the {@link PreparationResult#res}. It is delivered to
         * the {@link RetryRule} for decision.
         * It is not {@code null} iff {@link PreparationResult#resToDecide} is {@code null}.
         */
        @Nullable
        Throwable causeToDecide;

        private PreparationResult(HttpResponse res, @Nullable HttpResponse resToDecide,
                                  @Nullable Throwable causeToDecide) {
            // TODO: remove?
            assert res != null;
            assert (resToDecide == null) != (causeToDecide == null)
                    : resToDecide + ", " + causeToDecide;

            this.res = res;
            this.resToDecide = resToDecide;
            this.causeToDecide = causeToDecide;
        }

        static PreparationResult ofSuccess(HttpResponse res, HttpResponse resForRule) {
            return new PreparationResult(res, resForRule, null);
        }

        static PreparationResult ofFailure(Throwable cause) {
            return new PreparationResult(HttpResponse.ofFailure(cause), null, cause);
        }
    }

    /**
     * The state of this attempt.
     * The following state machine diagram illustrates the possible transitions:
     * <pre>
     *          Start
     *            |
     *           IDLE
     *            |
     *            v
     *        EXECUTING----------+
     *            |              |
     *            v              |
     *         DECIDED-----------+
     *          /   \            |
     *         /     \           |
     *        /       \          |
     *       v         v         |
     *   COMMITTED  ABORTED<-----+
     *       \         /
     *        +---+---+
     *            |
     *            v
     *           End
     * </pre>
     */
    enum State {
        /**
         *  The attempt was not executed yet via {@link #executeAndDecide(Client)}.
         */
        IDLE,
        /**
         * The attempt was executed by {@link #executeAndDecide(Client)}.
         *
         * <p>
         *  The response is being prepared for and given to the {@link RetryRule} to make a decision.
         *  In this context, 'preparing' means processing the response until the point where
         *  the {@link RetryRule} can make a decision (e.g., receiving headers, content, or trailers).
         *  {@link #res} is available from this state onwards.
         * </p>
         */
        EXECUTING,
        /**
         * The response was prepared and the {@link RetryRule} made a decision.
         * The attempt is ready to be committed via {@link #commit()} or aborted via {@link #abort(Throwable)}.
         */
        DECIDED,
        /**
         * The attempt was committed via {@link #commit()}. This is a terminal state.
         */
        COMMITTED,
        /**
         * The attempt was aborted via {@link #abort(Throwable)}. The response is *going to be*
         * aborted via the same cause. In this state and only in this state {@link #cause} is not-{@code null}.
         * This is a terminal state.
         */
        ABORTED
    }

    private final RetryConfig<HttpResponse> config;
    private final ContextAwareEventLoop retryEventLoop;
    private final int attemptNumber;
    private final ClientRequestContext ctx;
    private final HttpRequest req;

    private State state;

    /**
     * The response of the attempt. It is available in {@link State#EXECUTING},
     * {@link State#DECIDED}, and {@link State#COMMITTED} states.
     */
    @Nullable
    private HttpResponse res;

    /**
     * The cause of the attempt failure. It is not-{@code null} iff we are in {@link State#ABORTED} state.
     */
    @Nullable
    Throwable cause;

    HttpRetryAttempt(
            RetryConfig<HttpResponse> config,
            ContextAwareEventLoop retryEventLoop,
            int attemptNumber,
            ClientRequestContext ctx,
            HttpRequest req
    ) {
        this.config = config;
        this.retryEventLoop = retryEventLoop;
        this.attemptNumber = attemptNumber;
        this.ctx = ctx;
        this.req = req;

        state = State.IDLE;
    }

    /**
     * Executes the attempt, prepares and gives the response or the cause to the {@link RetryRule}.
     * This method must be called at most once and except for calls to {@link #abort(Throwable)}
     * it must be called before any other method, in particular before {@link #commit()}.
     *
     * @param delegate the next {@link Client} in the decoration chain
     * @return a future that will be completed with the {@link RetryDecision} or an exception if failed during
     *         execution, preparation, or decision.
     *         In particular, it fails with a {@link AbortedAttemptException}
     *         if the attempt was aborted by {@link #abort(Throwable)}.
     *
     * @see #commit()
     */
    CompletableFuture<RetryDecision> executeAndDecide(Client<HttpRequest, HttpResponse> delegate) {
        checkState(retryEventLoop.inEventLoop());

        if (state == State.ABORTED) {
            assert cause != null;
            return UnmodifiableFuture.exceptionallyCompletedFuture(cause);
        }

        checkState(state == State.IDLE);
        state = State.EXECUTING;

        res = execute(delegate);

        return prepareForDecision(res)
                .thenCompose(preparationResult -> {
                    assert retryEventLoop.inEventLoop();
                    // Let us avoid calling decide (which may be expensive if we already know that we were
                    // aborted.
                    if (state == State.ABORTED) {
                        assert cause != null;
                        assert preparationResult.res.isComplete();

                        if (preparationResult.resToDecide != null &&
                            preparationResult.res != preparationResult.resToDecide) {
                            preparationResult.resToDecide.abort(cause);
                        }

                        return UnmodifiableFuture.exceptionallyCompletedFuture(cause);
                    }

                    assert state == State.EXECUTING : state;

                    return decide(preparationResult.resToDecide,
                                  preparationResult.causeToDecide)
                            .whenComplete((unusedDecision, unusedDecisionCause) -> {
                                assert retryEventLoop.inEventLoop();
                                // Let us abort the response that was delivered to the rule as we do not need
                                // it anymore.
                                if (preparationResult.resToDecide != null &&
                                    // In case we delivered the attempt response
                                    preparationResult.res != preparationResult.resToDecide) {
                                    preparationResult.resToDecide.abort();
                                }
                            })
                            .thenCompose(
                                    decision -> {
                                        if (state == State.ABORTED) {
                                            assert cause != null;
                                            // Was aborted by {@link #abort()} while we were deciding.
                                            assert preparationResult.res.isComplete();
                                            return UnmodifiableFuture.exceptionallyCompletedFuture(cause);
                                        } else {
                                            assert state == State.EXECUTING : state;
                                            assert cause == null : cause; // sanity check
                                            assert res != null;
                                            res = preparationResult.res;
                                            state = State.DECIDED;
                                            return UnmodifiableFuture.completedFuture(decision);
                                        }
                                    }
                            );
                })
                .handle(
                        (decision, preparationOrDecisionCause) -> {
                            if (preparationOrDecisionCause == null) {
                                return (CompletableFuture<RetryDecision>) UnmodifiableFuture.completedFuture(
                                        decision);
                            }

                            if (state == State.ABORTED) {
                                assert cause != null;
                                if (cause != preparationOrDecisionCause) {
                                    cause.addSuppressed(preparationOrDecisionCause);
                                }
                                return UnmodifiableFuture.<RetryDecision>exceptionallyCompletedFuture(
                                        preparationOrDecisionCause);
                            }
                            assert state == State.EXECUTING : state;

                            abort(preparationOrDecisionCause);
                            return UnmodifiableFuture.<RetryDecision>exceptionallyCompletedFuture(
                                    preparationOrDecisionCause);
                        }
                )
                .thenCompose(Function.identity());
    }

    private HttpResponse execute(Client<HttpRequest, HttpResponse> delegate) {
        final boolean isInitialAttempt = attemptNumber <= 1;

        if (!isInitialAttempt) {
            ctx.mutateAdditionalRequestHeaders(
                    mutator -> mutator.add(ARMERIA_RETRY_COUNT, Integer.toString(attemptNumber - 1)));
        }

        final RequestHeadersBuilder attemptHeadersBuilder = req.headers().toBuilder();
        attemptHeadersBuilder.setInt(ARMERIA_RETRY_COUNT, attemptNumber - 1);

        final ClientRequestContextExtension ctxExt =
                ctx.as(ClientRequestContextExtension.class);
        if (!isInitialAttempt && ctxExt != null && ctx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(ctx);
            // if the endpoint hasn't been selected,
            // try to initialize the attempCtx with a new endpoint/event loop
            return initContextAndExecuteWithFallback(
                    delegate, ctxExt, HttpResponse::of,
                    (context, cause) ->
                            HttpResponse.ofFailure(cause), req, false);
        } else {
            return executeWithFallback(delegate, ctx,
                                       (context, cause) ->
                                               HttpResponse.ofFailure(cause), req, false);
        }
    }

    /**
     * Prepares the response for decision by the {@link RetryRule}.
     *
     * <p>
     *     In the context of this class, preparation means processing the response until the point where the
     *     {@link RetryRule} can make a decision (e.g., receiving headers, content, or trailers).
     *     The returned future is completed with a {@link PreparationResult} which contains the response
     *     for the {@link RetryRule} ({@link PreparationResult#resToDecide}) to make a decision
     *     and the new response of the attempt (@link PreparationResult#res}).
     * </p>
     *
     * <p>
     *     Note that {@link PreparationResult#res} might be different from the
     *     initial response stored in {@link #res} as we might need to transform it during preparation
     *     (e.g. by aggregation).
     * </p>
     *
     * @param res the response of the attempt
     * @return a future that will be completed with the {@link PreparationResult}
     */
    private CompletableFuture<PreparationResult> prepareForDecision(HttpResponse res) {
        final boolean aggregateResponse =
                !ctx.exchangeType().isResponseStreaming() || config.requiresResponseTrailers();
        return withCompletionOnRetryEventLoop(
                aggregateResponse ? prepareAggregatedResponse(res) : prepareStreamingResponse(res));
    }

    /**
     * Marks this attempt as committed and returns the response of the attempt.
     * The attempt must be decided, i.e. {@link #executeAndDecide(Client)} must have been called and completed
     * successfully before calling this method. After this call, the attempt cannot be aborted anymore.
     * This method is idempotent once the first call returns successfully.
     *
     * @return the response of the attempt
     *
     * @see #executeAndDecide(Client)
     */
    public HttpResponse commit() {
        checkState(retryEventLoop.inEventLoop());

        if (state == State.COMMITTED) {
            assert res != null;
            return res;
        }

        checkState(state == State.DECIDED);
        assert res != null;
        assert cause == null : cause;
        state = State.COMMITTED;
        return res;
    }

    /**
     * Aborts this attempt with the specified {@code cause}.
     * {@link #executeAndDecide(Client)} must be called before this method is called.
     * After this call, the attempt cannot be committed anymore.
     * This method is idempotent once the first call returns successfully.
     *
     * @param cause the cause of the abortion
     */
    public void abort(Throwable cause) {
        checkState(retryEventLoop.inEventLoop());

        if (state == State.ABORTED) {
            return;
        }

        assert state == State.IDLE ||
               state == State.EXECUTING ||
               state == State.DECIDED : state;
        assert this.cause == null : state;
        state = State.ABORTED;

        final RequestLogBuilder logBuilder = ctx.logBuilder();
        // Set response content with null to make sure that the log is complete.
        logBuilder.responseContent(null, null);
        logBuilder.responseContentPreview(null);

        if (res != null) {
            res.abort(cause);
        } else {
            // If we are in EXECUTING or PREPARING_AND_DECIDING, the execution/preparation must have failed.
        }
    }

    /**
     * Returns the {@link ClientRequestContext} of this attempt.
     *
     * @return the {@link ClientRequestContext} of this attempt
     */
    ClientRequestContext ctx() {
        return ctx;
    }

    /**
     * Returns the {@link State} of this attempt.
     *
     * @return the {@link State} of this attempt
     */
    State state() {
        return state;
    }

    private CompletableFuture<PreparationResult> prepareAggregatedResponse(HttpResponse res) {
        return res.aggregate()
                  .thenCompose(aggRes -> {
                      // NOTE: Might not run on the retry event loop.
                      completeLogIfBytesNotTransferred(aggRes);
                      return ctx.log()
                                .whenAvailable(RequestLogProperty.RESPONSE_END_TIME)
                                .thenApply(unused ->
                                                   // NOTE: Might not run on the retry event loop.
                                                   PreparationResult.ofSuccess(
                                                           aggRes.toHttpResponse(),
                                                           aggRes.toHttpResponse()
                                                   )
                                );
                  }).handle((preparationResult, resCause) -> {
                    // NOTE: Might not run on the retry event loop.
                    if (resCause == null) {
                        return UnmodifiableFuture.completedFuture(preparationResult);
                    }

                    resCause = Exceptions.peel(resCause);

                    ctx.logBuilder().endRequest(resCause);
                    ctx.logBuilder().endResponse(resCause);
                    return UnmodifiableFuture.completedFuture(PreparationResult.ofFailure(resCause));
                })
                  .thenCompose(Function.identity());
    }

    private CompletableFuture<PreparationResult> prepareStreamingResponse(HttpResponse res) {
        final SplitHttpResponse splitRes = res.split();

        return splitRes.headers()
                       .handle((resHeaders, headersCause) -> {
                           // NOTE: Might not run on the retry event loop.
                           final Throwable resCause;
                           if (headersCause == null) {
                               final RequestLog log = ctx.log().getIfAvailable(
                                       RequestLogProperty.RESPONSE_CAUSE);
                               resCause = log != null ? log.responseCause() : null;
                           } else {
                               resCause = Exceptions.peel(headersCause);
                           }

                           final HttpResponse unsplitRes = splitRes.unsplit();
                           completeLogIfBytesNotTransferred(unsplitRes, resHeaders, resCause);

                           return ctx.log()
                                     // NOTE: .whenAvailable is guaranteed to never complete exceptionally.
                                     .whenAvailable(RequestLogProperty.RESPONSE_HEADERS)
                                     .thenApply(unused -> {
                                         // NOTE: Might not run on the retry event loop.
                                         if (resCause != null) {
                                             unsplitRes.abort(resCause);
                                             return PreparationResult.ofFailure(resCause);
                                         }

                                         if (config.needsContentInRule()) {
                                             try (
                                                     HttpResponseDuplicator resDuplicator =
                                                             unsplitRes.toDuplicator(
                                                                     ctx.eventLoop().withoutContext(),
                                                                     ctx.maxResponseLength()
                                                             )
                                             ) {
                                                 return PreparationResult.ofSuccess(
                                                         resDuplicator.duplicate(),
                                                         new TruncatingHttpResponse(
                                                                 resDuplicator.duplicate(),
                                                                 config.maxContentLength()
                                                         )
                                                 );
                                             }
                                         } else {
                                             return PreparationResult.ofSuccess(
                                                     unsplitRes,
                                                     unsplitRes
                                             );
                                         }
                                     });
                       })
                       .thenCompose(Function.identity());
    }

    private void completeLogIfBytesNotTransferred(AggregatedHttpResponse aggRes) {
        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder attemptLogBuilder = ctx.logBuilder();
            attemptLogBuilder.endRequest();
            attemptLogBuilder.responseHeaders(aggRes.headers());
            if (!aggRes.trailers().isEmpty()) {
                attemptLogBuilder.responseTrailers(aggRes.trailers());
            }
            attemptLogBuilder.endResponse();
        }
    }

    private void completeLogIfBytesNotTransferred(
            HttpResponse res,
            @Nullable ResponseHeaders headers,
            @Nullable Throwable resCause) {
        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder logBuilder = ctx.logBuilder();
            if (resCause != null) {
                logBuilder.endRequest(resCause);
                logBuilder.endResponse(resCause);
            } else {
                logBuilder.endRequest();
                if (headers != null) {
                    logBuilder.responseHeaders(headers);
                }
                res.whenComplete().handle((unused, cause) -> {
                    if (cause != null) {
                        logBuilder.endResponse(cause);
                    } else {
                        logBuilder.endResponse();
                    }
                    return null;
                });
            }
        }
    }

    /**
     * Calls {@link RetryRule#shouldRetry(ClientRequestContext, Throwable)} to receive a {@link RetryDecision}.
     *
     * @param resForRule the response to be delivered to the {@link RetryRule} for decision.
     * @param causeForRule the cause to be delivered to the {@link RetryRule} for decision.
     * @return a future that will be completed with the {@link RetryDecision} or an exception if failed during
     *        the decision.
     */
    private CompletableFuture<RetryDecision> decide(@Nullable HttpResponse resForRule,
                                                    @Nullable Throwable causeForRule) {
        if (causeForRule != null) {
            resForRule = null;
            causeForRule = Exceptions.peel(causeForRule);
        } else {
            assert resForRule != null;
            causeForRule = null;
        }

        final CompletionStage<@Nullable RetryDecision> maybeDecision;

        if (config.needsContentInRule()) {
            final RetryRuleWithContent<HttpResponse> retryRuleWithContent =
                    config.retryRuleWithContent();
            assert retryRuleWithContent != null;
            try {
                maybeDecision = retryRuleWithContent.shouldRetry(ctx, resForRule, causeForRule);
            } catch (Throwable t) {
                return UnmodifiableFuture.exceptionallyCompletedFuture(t);
            }
        } else {
            final RetryRule retryRule = config.retryRule();
            assert retryRule != null;
            try {
                maybeDecision = retryRule.shouldRetry(ctx, causeForRule);
            } catch (Throwable t) {
                return UnmodifiableFuture.exceptionallyCompletedFuture(t);
            }
        }

        // Remember that RetryRule.shouldRetry could be client code so we do have any guarantees
        // on which thread we are completing. Let us make sure we are completing on running on the
        // retry event loop again.
        return withCompletionOnRetryEventLoop(
                maybeDecision.thenApply(
                        decision -> decision == null ? RetryDecision.noRetry() : decision
                )
        );
    }

    private <T> CompletableFuture<T> withCompletionOnRetryEventLoop(CompletionStage<T> future) {
        final CompletableFuture<T> futureOnTheRetryEventLoop = new CompletableFuture<>();
        future.whenComplete((result, cause) -> {
            if (retryEventLoop.inEventLoop()) {
                if (cause != null) {
                    futureOnTheRetryEventLoop.completeExceptionally(cause);
                } else {
                    futureOnTheRetryEventLoop.complete(result);
                }
            } else {
                retryEventLoop.execute(() -> {
                    if (cause != null) {
                        futureOnTheRetryEventLoop.completeExceptionally(cause);
                    } else {
                        futureOnTheRetryEventLoop.complete(result);
                    }
                });
            }
        });
        return futureOnTheRetryEventLoop;
    }

    @Override
    public String toString() {
        checkState(retryEventLoop.inEventLoop());

        return MoreObjects
                .toStringHelper(this)
                .add("state", state)
                .add("attemptNumber", attemptNumber)
                .add("ctx", ctx)
                .add("req", req)
                .add("res", res)
                .add("cause", cause)
                .toString();
    }
}
