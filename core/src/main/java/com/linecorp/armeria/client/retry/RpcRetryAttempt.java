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
import com.linecorp.armeria.common.ContextAwareEventLoop;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;

/**
 * A single attempt for a {@link RetriedRpcRequest}.
 *
 *  <p>
 *   NOTE: All methods of {@link RpcRetryAttempt} must be invoked from the
 *   {@code retryEventLoop}.
 *  </p>
 */
final class RpcRetryAttempt {
    /**
     * The state of this attempt.
     *
     * <p>This state machine controls the retry attempt lifecycle. All state transitions
     * must occur on the {@code retryEventLoop} thread to ensure consistency.
     *
     * <p>The following state machine diagram illustrates the possible transitions:
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
         * The attempt has not been executed yet.
         * Call {@link #executeAndDecide(Client)} to transition to {@link #EXECUTING}.
         */
        IDLE,

        /**
         * The attempt is executing and waiting for a response or exception.
         * The {@link #res} field becomes available in this state.
         * Can transition to {@link #DECIDED} on completion or {@link #ABORTED} if aborted.
         */
        EXECUTING,

        /**
         * The {@link RetryRule} has made a decision about whether to retry.
         * The attempt is ready to be either committed via {@link #commit()}
         * or aborted via {@link #abort(Throwable)}.
         */
        DECIDED,

        /**
         * The attempt was successfully committed via {@link #commit()}.
         * This is a terminal state and the response is final.
         */
        COMMITTED,

        /**
         * The attempt was aborted via {@link #abort(Throwable)}.
         * The {@link #cause} field is non-null only in this state.
         * This is a terminal state.
         */
        ABORTED
    }

    private final RetryConfig<RpcResponse> config;
    private final ContextAwareEventLoop retryEventLoop;
    private final int attemptNumber;
    private final ClientRequestContext ctx;
    private final RpcRequest req;

    private State state;

    /**
     * The response of the attempt. It is available in {@link State#EXECUTING},
     * {@link State#DECIDED}, and {@link State#COMMITTED} states.
     */
    @Nullable
    private RpcResponse res;

    /**
     * The cause of the attempt failure. It is not-{@code null} iff we are in {@link State#ABORTED} state.
     */
    @Nullable
    Throwable cause;

    RpcRetryAttempt(
            RetryConfig<RpcResponse> config,
            ContextAwareEventLoop retryEventLoop,
            int attemptNumber,
            ClientRequestContext ctx,
            RpcRequest req
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
    CompletionStage<RetryDecision> executeAndDecide(Client<RpcRequest, RpcResponse> delegate) {
        checkState(retryEventLoop.inEventLoop());

        if (state == State.ABORTED) {
            assert cause != null;
            return UnmodifiableFuture.exceptionallyCompletedFuture(cause);
        }

        checkState(state == State.IDLE);
        state = State.EXECUTING;

        res = execute(delegate);

        return res
                .handle((unused, causeToDecide) -> {
                    assert retryEventLoop.inEventLoop();
                    // Let us avoid calling decide (which may be expensive if we already know that we were
                    // aborted.
                    if (state == State.ABORTED) {
                        assert cause != null;
                        return UnmodifiableFuture.<RetryDecision>exceptionallyCompletedFuture(cause);
                    }

                    assert state == State.EXECUTING : state;

                    return decide(res, causeToDecide)
                            .thenCompose(
                                    decision -> {
                                        if (state == State.ABORTED) {
                                            assert cause != null;
                                            return UnmodifiableFuture.exceptionallyCompletedFuture(
                                                    cause);
                                        } else {
                                            assert state == State.EXECUTING : state;
                                            assert cause == null : cause; // sanity check
                                            assert res != null;
                                            state = State.DECIDED;
                                            return UnmodifiableFuture.completedFuture(decision);
                                        }
                                    }
                            );
                })
                .thenCompose(Function.identity())
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

    private RpcResponse execute(Client<RpcRequest, RpcResponse> delegate) {
        final boolean isInitialAttempt = attemptNumber <= 1;

        if (!isInitialAttempt) {
            ctx.mutateAdditionalRequestHeaders(
                    mutator -> mutator.add(ARMERIA_RETRY_COUNT, Integer.toString(attemptNumber - 1)));
        }

        final ClientRequestContextExtension ctxExt =
                ctx.as(ClientRequestContextExtension.class);
        if (!isInitialAttempt && ctxExt != null && ctx.endpointGroup() != null && ctx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(ctx);
            // if the endpoint hasn't been selected,
            // try to initialize the attempCtx with a new endpoint/event loop
            return initContextAndExecuteWithFallback(
                    delegate, ctxExt, RpcResponse::from,
                    (context, cause) ->
                            RpcResponse.ofFailure(cause), req, true);
        } else {
            return executeWithFallback(delegate, ctx,
                                       (context, cause) ->
                                               RpcResponse.ofFailure(cause), req, true);
        }
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
    public RpcResponse commit() {
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
        assert this.cause == null : this.cause;
        state = State.ABORTED;
        this.cause = cause;
        ctx.cancel();
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

    /**
     * Calls {@link RetryRule#shouldRetry(ClientRequestContext, Throwable)} to receive a {@link RetryDecision}.
     *
     * @param resForRule the response to be delivered to the {@link RetryRule} for decision.
     * @param causeForRule the cause to be delivered to the {@link RetryRule} for decision.
     * @return a future that will be completed with the {@link RetryDecision} or an exception if failed during
     *        the decision. The future will be completed on the retry event loop.
     */
    private CompletableFuture<RetryDecision> decide(@Nullable RpcResponse resForRule,
                                                    @Nullable Throwable causeForRule) {
        if (causeForRule != null) {
            resForRule = null;
            causeForRule = Exceptions.peel(causeForRule);
        } else {
            assert resForRule != null;
            causeForRule = null;
        }

        final RetryRuleWithContent<RpcResponse> retryRule =
                config.needsContentInRule() ? config.retryRuleWithContent()
                                            : config.fromRetryRule();
        assert retryRule != null;

        try {
            final CompletionStage<@Nullable RetryDecision> maybeDecision = retryRule
                    .shouldRetry(ctx,
                                 resForRule,
                                 causeForRule);

            // Remember that RetryRule.shouldRetry could be client code so we do have any guarantees
            // on which thread we are completing. Let us make sure we are completing on running on the
            // retry event loop again.
            return withCompletionOnRetryEventLoop(
                    maybeDecision.thenApply(
                            decision -> decision == null ? RetryDecision.noRetry() : decision
                    )
            );
        } catch (Throwable t) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(t);
        }
    }

    /**
     * Ensures the given {@link CompletionStage} completes on the retry event loop.
     * If already on the retry event loop, completes directly; otherwise schedules completion.
     */
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
