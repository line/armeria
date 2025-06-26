/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.AggregatedHttpRequestDuplicator;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

import io.netty.handler.codec.DateFormatter;

/**
 * An {@link HttpClient} decorator that handles failures of an invocation and retries HTTP requests.
 */
public final class RetryingClient extends AbstractRetryingClient<HttpRequest, HttpResponse>
        implements HttpClient {

    private static final Logger logger = LoggerFactory.getLogger(RetryingClient.class);

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryConfig}.
     * The {@link RetryConfig} object encapsulates {@link RetryRule} or {@link RetryRuleWithContent},
     * {@code maxContentLength}, {@code maxTotalAttempts} and {@code responseTimeoutMillisForEachAttempt}.
     */
    public static RetryingClientBuilder builder(RetryConfig<HttpResponse> retryConfig) {
        return new RetryingClientBuilder(retryConfig);
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRule}.
     */
    public static RetryingClientBuilder builder(RetryRule retryRule) {
        return new RetryingClientBuilder(RetryConfig.<HttpResponse>builder0(retryRule).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRuleWithContent}.
     */
    public static RetryingClientBuilder builder(RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return new RetryingClientBuilder(RetryConfig.builder0(retryRuleWithContent).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRuleWithContent} and
     * the specified {@code maxContentLength}.
     * The {@code maxContentLength} required to determine whether to retry or not. If the total length of
     * content exceeds this length and there's no retry condition matched,
     * it will hand over the stream to the client.
     *
     * @throws IllegalArgumentException if the specified {@code maxContentLength} is equal to or
     *                                  less than {@code 0}
     */
    public static RetryingClientBuilder builder(RetryRuleWithContent<HttpResponse> retryRuleWithContent,
                                                int maxContentLength) {
        checkArgument(maxContentLength > 0, "maxContentLength: %s (expected: > 0)", maxContentLength);
        return new RetryingClientBuilder(
                RetryConfig.builder0(retryRuleWithContent).maxContentLength(maxContentLength).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryConfigMapping}.
     */
    public static RetryingClientBuilder builderWithMapping(RetryConfigMapping<HttpResponse> mapping) {
        return new RetryingClientBuilder(mapping);
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     */
    public static Function<? super HttpClient, RetryingClient> newDecorator(RetryRule retryRule) {
        return builder(retryRule).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return builder(retryRuleWithContent).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     * @param maxTotalAttempts the maximum allowed number of total attempts
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRule retryRule, int maxTotalAttempts) {
        return builder(retryRule).maxTotalAttempts(maxTotalAttempts).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     * @param maxTotalAttempts the maximum allowed number of total attempts
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent, int maxTotalAttempts) {
        return builder(retryRuleWithContent).maxTotalAttempts(maxTotalAttempts).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRule retryRule, int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        return builder(retryRule).maxTotalAttempts(maxTotalAttempts)
                                 .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                                 .newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent, int maxTotalAttempts,
                 long responseTimeoutMillisForEachAttempt) {
        return builder(retryRuleWithContent)
                .maxTotalAttempts(maxTotalAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                .newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     * The {@link RetryConfig} object encapsulates {@link RetryRule} or {@link RetryRuleWithContent},
     * {@code maxContentLength}, {@code maxTotalAttempts} and {@code responseTimeoutMillisForEachAttempt}.
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryConfig<HttpResponse> retryConfig) {
        return builder(retryConfig).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param mapping the mapping that returns a {@link RetryConfig} for a given {@link ClientRequestContext}
     *        and {@link Request}.
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecoratorWithMapping(RetryConfigMapping<HttpResponse> mapping) {
        return builderWithMapping(mapping).newDecorator();
    }

    private final boolean useRetryAfter;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    RetryingClient(
            HttpClient delegate,
            RetryConfigMapping<HttpResponse> mapping,
            @Nullable RetryConfig<HttpResponse> retryConfig,
            boolean useRetryAfter) {
        super(delegate, mapping, retryConfig);
        this.useRetryAfter = useRetryAfter;
    }

    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> resFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.of(resFuture, ctx.eventLoop());
        if (ctx.exchangeType().isRequestStreaming()) {
            final HttpRequestDuplicator reqDuplicator =
                    req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
            final RetryingContext rctx = new RetryingContext(
                    ctx, mappedRetryConfig(ctx), req, reqDuplicator, res, resFuture);
            final Attempt attempt = new Attempt(rctx, unwrap(), 1);
            doExecuteAttempt(rctx, attempt);
        } else {
            req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
               .handle((agg, reqCause) -> {
                   if (reqCause != null) {
                       resFuture.completeExceptionally(reqCause);
                       ctx.logBuilder().endRequest(reqCause);
                       ctx.logBuilder().endResponse(reqCause);
                   } else {
                       final HttpRequestDuplicator reqDuplicator = new AggregatedHttpRequestDuplicator(agg);
                       final RetryingContext rctx = new RetryingContext(
                               ctx, mappedRetryConfig(ctx), req, reqDuplicator, res, resFuture);
                       final Attempt attempt = new Attempt(rctx, unwrap(), 1);
                       doExecuteAttempt(rctx, attempt);
                   }
                   return null;
               });
        }
        return res;
    }

    private void doExecuteAttempt(RetryingContext rctx, Attempt attempt) {
        assert attempt.state() == Attempt.State.INITIALIZED;
        final boolean isInitialAttempt = attempt.number() <= 1;
        // The request or response has been aborted by the client before it receives a response,
        // so stop retrying.
        if (rctx.req().whenComplete().isCompletedExceptionally()) {
            rctx.req().whenComplete().handle((unused, cause) -> {
                abort(rctx, cause, isInitialAttempt);
                return null;
            });
            return;
        }
        if (rctx.res().isComplete()) {
            rctx.res().whenComplete().handle((result, cause) -> {
                final Throwable abortCause;
                if (cause != null) {
                    abortCause = cause;
                } else {
                    abortCause = AbortedStreamException.get();
                }
                abort(rctx, abortCause, isInitialAttempt);
                return null;
            });
            return;
        }

        if (!setResponseTimeout(rctx.ctx())) {
            abort(rctx, ResponseTimeoutException.get(), isInitialAttempt);
            return;
        }

        attempt.execute().handle((attemptProposal, unexpectedAttemptCause) -> {
            if (unexpectedAttemptCause != null) {
                assert attempt.state() == Attempt.State.ABORTED;
                abort(rctx, unexpectedAttemptCause);
                return null;
            }

            assert attempt.state() == Attempt.State.PROPOSED;

            decideOnAttempt(rctx, attempt).handle((nextDelayMillis, unexpectedDecisionCause) -> {
                if (unexpectedDecisionCause != null) {
                    attempt.abort(unexpectedDecisionCause);
                    assert attempt.state() == Attempt.State.ABORTED;
                    abort(rctx, unexpectedDecisionCause);
                    return null;
                }

                assert attempt.state() == Attempt.State.PROPOSED;

                if (nextDelayMillis >= 0) {
                    attempt.abort();
                    assert attempt.state() == Attempt.State.ABORTED;

                    scheduleNextRetry(
                            rctx.ctx(), schedulingCause -> abort(rctx, schedulingCause),
                            () -> {
                                final Attempt nextAttempt = new Attempt(rctx, unwrap(), attempt.number() + 1);
                                doExecuteAttempt(rctx, nextAttempt);
                            },
                            nextDelayMillis);
                    return null;
                }

                commit(rctx, attempt);
                assert attempt.state() == Attempt.State.COMMITTED;
                return null;
            });

            return null;
        });
    }

    private CompletionStage<Long> decideOnAttempt(RetryingContext rctx, Attempt attempt) {
        assert attempt.state() == Attempt.State.PROPOSED;
        final Attempt.Result result = attempt.result();
        final CompletionStage<@Nullable RetryDecision> retryDecisionFuture;

        if (rctx.config().needsContentInRule()) {
            final RetryRuleWithContent<HttpResponse> retryRuleWithContent =
                    rctx.config().retryRuleWithContent();
            assert retryRuleWithContent != null;
            retryDecisionFuture = retryRuleWithContent
                    .shouldRetry(result.ctx(), result.res(), result.cause())
                    .exceptionally(cause -> {
                        logger.warn("Unexpected exception is raised from {}.", retryRuleWithContent, cause);
                        return null;
                    });
        } else {
            final RetryRule retryRule = rctx.config().retryRule();
            assert retryRule != null;
            retryDecisionFuture = retryRule.shouldRetry(result.ctx(), result.cause())
                                           .exceptionally(cause -> {
                                               logger.warn("Unexpected exception is raised from {}.", retryRule,
                                                           cause);
                                               return null;
                                           });
        }

        return retryDecisionFuture.handle((decision, cause) -> {
            assert cause == null;
            assert attempt.state() == Attempt.State.PROPOSED;
            final Backoff backoff = decision != null ? decision.backoff() : null;
            if (backoff != null) {
                final long millisAfter = useRetryAfter ? getRetryAfterMillis(result.ctx()) : -1;
                final long nextDelay = getNextDelay(result.ctx(), backoff, millisAfter);
                if (nextDelay >= 0) {
                    return nextDelay;
                }
            }

            return -1L;
        });
    }

    private static void commit(RetryingContext rctx, Attempt attempt) {
        assert attempt.state() == Attempt.State.PROPOSED;
        attempt.commit();
        assert attempt.state() == Attempt.State.COMMITTED;
        final Attempt.Result attemptResultToCommit = attempt.result();
        onRetryingComplete(rctx.ctx());
        rctx.resFuture().complete(attemptResultToCommit.res());
        rctx.reqDuplicator().close();
    }

    private static void abort(RetryingContext rctx, Throwable cause) {
        abort(rctx, cause, false);
    }

    private static void abort(RetryingContext rctx, Throwable cause, boolean endRequestLog) {
        rctx.resFuture().completeExceptionally(cause);
        rctx.reqDuplicator().abort(cause);
        if (endRequestLog) {
            rctx.ctx().logBuilder().endRequest(cause);
        }
        rctx.ctx().logBuilder().endResponse(cause);
    }

    private static long getRetryAfterMillis(ClientRequestContext attemptCtx) {
        final RequestLogAccess attemptLog = attemptCtx.log();
        final String retryAfterValue;
        final RequestLog requestLog = attemptLog.getIfAvailable(RequestLogProperty.RESPONSE_HEADERS);
        retryAfterValue = requestLog != null ?
                          requestLog.responseHeaders().get(HttpHeaderNames.RETRY_AFTER) : null;

        if (retryAfterValue != null) {
            try {
                return Duration.ofSeconds(Integer.parseInt(retryAfterValue)).toMillis();
            } catch (Exception ignored) {
                // Not a second value.
            }

            try {
                @SuppressWarnings("UseOfObsoleteDateTimeApi")
                final Date retryAfterDate = DateFormatter.parseHttpDate(retryAfterValue);
                if (retryAfterDate != null) {
                    return retryAfterDate.getTime() - System.currentTimeMillis();
                }
            } catch (Exception ignored) {
                // `parseHttpDate()` can raise an exception rather than returning `null`
                // when the given value has more than 64 characters.
            }

            logger.debug("The retryAfter: {}, from the server is neither an HTTP date nor a second.",
                         retryAfterValue);
        }

        return -1;
    }

    private static class RetryingContext {
        private final ClientRequestContext ctx;
        private final RetryConfig<HttpResponse> retryConfig;
        private final HttpRequest req;
        private final HttpRequestDuplicator reqDuplicator;
        private final HttpResponse res;
        private final CompletableFuture<HttpResponse> resFuture;

        RetryingContext(ClientRequestContext ctx,
                        RetryConfig<HttpResponse> retryConfig,
                        HttpRequest req,
                        HttpRequestDuplicator reqDuplicator,
                        HttpResponse res,
                        CompletableFuture<HttpResponse> resFuture) {

            this.ctx = ctx;
            this.retryConfig = retryConfig;
            this.req = req;
            this.reqDuplicator = reqDuplicator;
            this.res = res;
            this.resFuture = resFuture;
        }

        RetryConfig<HttpResponse> config() {
            return retryConfig;
        }

        ClientRequestContext ctx() {
            return ctx;
        }

        HttpRequestDuplicator reqDuplicator() {
            return reqDuplicator;
        }

        HttpRequest req() {
            return req;
        }

        HttpResponse res() {
            return res;
        }

        CompletableFuture<HttpResponse> resFuture() {
            return resFuture;
        }
    }

    private static class Attempt {
        private enum State {
            // State of the attempt after construction and before the call to execute().
            INITIALIZED,
            // State of the attempt before the outer call to execute() and before the inner call to propose().
            EXECUTING,
            // State of the attempt before the call to commit() and before the outer call to commit() or abort()
            // It means that this attempt ended up with a response, with or without content depending on the
            // RetryRule, and it is up to the caller to decide on whether to commit on this response (i.e.,
            // returning this response from this client) or to abort it we are continue retrying).
            PROPOSED,
            // After the outer call to commit(). Terminal state.
            COMMITTED,
            ABORTED
            // After the outer call to abort() or after an unexpected exception. Terminal state.
            // After setting this state, all attempt-related resources are freed.
        }

        static class Result {
            private final ClientRequestContext ctx;
            @Nullable
            private final HttpResponse res;
            @Nullable
            private final Throwable cause;

            Result(ClientRequestContext ctx, @Nullable HttpResponse res, @Nullable Throwable cause) {
                this.ctx = ctx;
                this.res = res;
                this.cause = cause;
            }

            ClientRequestContext ctx() {
                return ctx;
            }

            @Nullable
            HttpResponse res() {
                return res;
            }

            @Nullable
            Throwable cause() {
                return cause;
            }

            void close() {
                if (res != null) {
                    // abort() is idempotent
                    res.abort();
                }
            }
        }

        private State state;

        @Nullable
        private Result result;
        private final CompletableFuture<Result> proposalFuture;

        RetryingContext rctx;

        final int number;

        // Available only in Attempt.State.EXECUTING.
        @Nullable
        private ClientRequestContext ctx;
        @Nullable
        private HttpResponse res;

        Client<HttpRequest, HttpResponse> delegate;

        Attempt(RetryingContext rctx, Client<HttpRequest, HttpResponse> delegate, int number) {
            this.rctx = rctx;
            this.delegate = delegate;
            this.number = number;
            proposalFuture = new CompletableFuture<>();
            state = State.INITIALIZED;
        }

        int number() {
            return number;
        }

        State state() {
            return state;
        }

        CompletableFuture<Result> execute() {
            assert state == State.INITIALIZED;
            final boolean isInitialAttempt = number <= 1;

            final HttpRequest attemptReq;
            if (isInitialAttempt) {
                attemptReq = rctx.reqDuplicator().duplicate();
            } else {
                final RequestHeadersBuilder attemptHeadersBuilder = rctx.req().headers().toBuilder();
                attemptHeadersBuilder.setInt(ARMERIA_RETRY_COUNT, number - 1);
                attemptReq = rctx.reqDuplicator().duplicate(attemptHeadersBuilder.build());
            }

            try {
                ctx = newAttemptContext(
                        rctx.ctx(), attemptReq, rctx.ctx().rpcRequest(), isInitialAttempt);
            } catch (Throwable t) {
                abort(t);
                return proposalFuture;
            }

            executeAttemptRequest();
            assert state == State.EXECUTING && res != null && ctx != null;

            if (!rctx.ctx().exchangeType().isResponseStreaming() || rctx.config().requiresResponseTrailers()) {
                handleAggRes();
            } else {
                handleStreamingRes();
            }

            return proposalFuture;
        }

        Result result() {
            assert state == State.PROPOSED || state == State.COMMITTED;
            assert result != null;
            return result;
        }

        void commit() {
            if (state == State.COMMITTED) {
                return;
            }
            assert state == State.PROPOSED;
            state = State.COMMITTED;

            if (result != null) {
                result.close();
            }
        }

        void abort() {
            abort(AbortedStreamException.get());
        }

        void abort(Throwable cause) {
            if (state == State.ABORTED) {
                return;
            }
            assert state == State.EXECUTING || state == State.PROPOSED;
            assert ctx != null && res != null;
            state = State.ABORTED;

            if (result != null) {
                // Frees intermediate resources.
                result.close();
            }

            final RequestLogBuilder logBuilder = ctx.logBuilder();
            // Set response content with null to make sure that the log is complete.
            logBuilder.responseContent(null, null);
            logBuilder.responseContentPreview(null);
            res.abort(cause);

            if (!proposalFuture.isDone()) {
                proposalFuture.completeExceptionally(cause);
            }
        }

        private void executeAttemptRequest() {
            assert state == State.INITIALIZED;
            assert ctx != null;
            final HttpRequest req = ctx.request();
            assert req != null;
            final ClientRequestContextExtension ctxExtension =
                    ctx.as(ClientRequestContextExtension.class);
            if ((number > 1) && ctxExtension != null && ctx.endpoint() == null) {
                // clear the pending throwable to retry endpoint selection
                ClientPendingThrowableUtil.removePendingThrowable(ctx);
                // if the endpoint hasn't been selected,
                // try to initialize the ctx with a new endpoint/event loop
                res = initContextAndExecuteWithFallback(
                        delegate, ctxExtension, HttpResponse::of,
                        (context, cause) ->
                                HttpResponse.ofFailure(cause), req, false);
            } else {
                res = executeWithFallback(delegate, ctx,
                                          (context, cause) ->
                                                  HttpResponse.ofFailure(cause), req, false);
            }

            state = State.EXECUTING;
        }

        private void handleAggRes() {
            assert state == State.EXECUTING;
            assert ctx != null && res != null;

            res.aggregate().handle((aggRes, resCause) -> {
                assert state == State.EXECUTING;
                assert ctx != null && res != null;

                if (resCause != null) {
                    ctx.logBuilder().endRequest(resCause);
                    ctx.logBuilder().endResponse(resCause);
                    proposeResult(HttpResponse.ofFailure(resCause), resCause);
                } else {
                    completeLogIfBytesNotTransferred(aggRes);
                    ctx.log().whenAvailable(RequestLogProperty.RESPONSE_END_TIME).thenRun(() -> {
                        proposeResult(aggRes.toHttpResponse(), null);
                    });
                }
                return null;
            });
        }

        private void handleStreamingRes() {
            assert state == State.EXECUTING;
            assert ctx != null && res != null;

            final SplitHttpResponse splitRes = res.split();
            splitRes.headers().handle((resHeaders, headersCause) -> {
                assert state == State.EXECUTING;
                assert ctx != null && res != null;

                final Throwable resCause;
                if (headersCause == null) {
                    final RequestLog log = ctx.log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
                    resCause = log != null ? log.responseCause() : null;
                } else {
                    resCause = Exceptions.peel(headersCause);
                }
                completeLogIfBytesNotTransferred(resHeaders, resCause);

                ctx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).thenRun(() -> {
                    assert state == State.EXECUTING;
                    assert ctx != null && res != null;

                    if (rctx.config().needsContentInRule() && resCause == null) {
                        final HttpResponse unsplitRes = splitRes.unsplit();
                        final HttpResponseDuplicator resDuplicator =
                                unsplitRes.toDuplicator(ctx.eventLoop().withoutContext(),
                                                        ctx.maxResponseLength());
                        try {
                            res = resDuplicator.duplicate();
                            final TruncatingHttpResponse truncatingAttemptRes =
                                    new TruncatingHttpResponse(resDuplicator.duplicate(),
                                                               rctx.config().maxContentLength());
                            resDuplicator.close();
                            proposeResult(truncatingAttemptRes, null);
                        } catch (Throwable cause) {
                            resDuplicator.abort(cause);
                            abort(cause);
                        }
                    } else {
                        final HttpResponse unsplitRes;
                        if (resCause != null) {
                            splitRes.body().abort(resCause);
                            unsplitRes = HttpResponse.ofFailure(resCause);
                        } else {
                            unsplitRes = splitRes.unsplit();
                        }

                        proposeResult(unsplitRes, resCause);
                    }
                });
                return null;
            });
        }

        private void proposeResult(@Nullable HttpResponse resToPropose, @Nullable  Throwable resCause) {
            assert state == State.EXECUTING;
            assert ctx != null && res != null;

            if (resCause != null) {
                resCause = Exceptions.peel(resCause);
            }

            state = State.PROPOSED;
            result = new Result(ctx, resToPropose, resCause);
            proposalFuture.complete(result);
        }

        private void completeLogIfBytesNotTransferred(AggregatedHttpResponse aggRes) {
            assert state == State.EXECUTING;
            assert ctx != null && res != null;

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
                @Nullable ResponseHeaders headers,
                @Nullable Throwable resCause) {
            assert state == State.EXECUTING;
            assert ctx != null && res != null;

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
    }
}
