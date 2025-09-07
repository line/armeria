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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.ContextAwareEventLoop;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.ClientUtil;

/**
 * Manages retry attempts for an RPC request, coordinating execution and decision-making
 * across multiple attempts until one succeeds or all retry options are exhausted.
 *
 * <p>All state mutations must occur on the {@code retryEventLoop} thread.
 */
class RetriedRpcRequest implements RetriedRequest<RpcRequest, RpcResponse> {
    private enum State {
        /**
         * Initial state. {@link RetriedRpcRequest} is instantiated but not yet initialized.
         */
        IDLE,
        /**
         * {@link RetriedRpcRequest} is initialized and is ready to execute/ is executing attempts.
         */
        PENDING,
        /**
         * Terminal state. Either {@link #commit(int)} or {@link #abort(Throwable)} was called.
         * {@link RetriedRpcRequest} will not execute any more attempts in that it completes every call
         * to {@link #executeAttempt(Client)} with an {@link AbortedAttemptException}.
         */
        COMPLETED
    }

    private final ContextAwareEventLoop retryEventLoop;
    private final RetryConfig<RpcResponse> config;
    private final ClientRequestContext ctx;
    private final RpcResponse res;
    private final CompletableFuture<RpcResponse> resFuture;
    private final RpcRequest req;

    private final long deadlineTimeNanos;

    private State state;

    int previousAttemptNumber;

    final ArrayList<RpcRetryAttempt> attempts;

    RetriedRpcRequest(
            ContextAwareEventLoop retryEventLoop,
            RetryConfig<RpcResponse> config,
            ClientRequestContext ctx,
            RpcRequest req,
            RpcResponse res, CompletableFuture<RpcResponse> resFuture
    ) {
        this.retryEventLoop = retryEventLoop;
        this.ctx = ctx;
        this.res = res;
        this.resFuture = resFuture;
        this.req = req;
        this.config = config;

        final long responseTimeoutMillis = ctx.responseTimeoutMillis();
        if (responseTimeoutMillis <= 0 || responseTimeoutMillis == Long.MAX_VALUE) {
            deadlineTimeNanos = Long.MAX_VALUE;
        } else {
            deadlineTimeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
        }

        state = State.IDLE;
        previousAttemptNumber = 0;
        // We can assume that most of the time we will succeed with the first attempt.
        attempts = new ArrayList<>(1);
    }

    @Override
    public CompletionStage<AttemptExecutionResult> executeAttempt(
            Client<RpcRequest, RpcResponse> delegate) {
        checkState(ctx.eventLoop().inEventLoop());
        init();
        return executeAttemptAfterInit(delegate);
    }

    /**
     * Initializes the {@link RetriedRpcRequest}. This method is idempotent.
     * Initialization mean subscribing to the original {@link RpcResponse} so that we can
     * abort all attempts when the original {@link RpcResponse} is completed.
     */
    private void init() {
        assert ctx.eventLoop().inEventLoop();

        if (state == State.PENDING || state == State.COMPLETED) {
            return;
        }

        assert state == State.IDLE : state;
        state = State.PENDING;

        res.whenComplete().handle((result, cause) -> {
            final Throwable abortCause;
            if (cause != null) {
                abortCause = cause;
            } else {
                abortCause = AbortedStreamException.get();
            }
            if (retryEventLoop.inEventLoop()) {
                abort(abortCause);
            } else {
                retryEventLoop.execute(() -> abort(abortCause));
            }
            return null;
        });
    }

    private CompletionStage<AttemptExecutionResult> executeAttemptAfterInit(
            Client<RpcRequest, RpcResponse> delegate) {
        assert retryEventLoop.inEventLoop();

        if (state == State.COMPLETED) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(AbortedAttemptException.get());
        }

        // We need to be initialized/ the reqDuplicator must be present.
        checkState(state == State.PENDING);
        checkState(previousAttemptNumber < config.maxTotalAttempts());

        final int attemptNumber = ++previousAttemptNumber;

        assert attemptNumber == attempts.size() + 1 : attemptNumber + ", " + attempts.size();
        assert attemptNumber <= config.maxTotalAttempts() : attemptNumber + ", " + config.maxTotalAttempts();

        if (!ClientUtil.checkAndSetResponseTimeout(ctx, deadlineTimeNanos,
                                                   config.responseTimeoutMillisForEachAttempt())) {
            final ResponseTimeoutException timeoutException = ResponseTimeoutException.get();
            abort(timeoutException);
            return UnmodifiableFuture.exceptionallyCompletedFuture(timeoutException);
        }

        final RpcRetryAttempt attempt = newAttempt(attemptNumber);
        attempts.add(attempt);
        return attempt
                .executeAndDecide(delegate)
                .thenApply(decision -> {
                    assert retryEventLoop.inEventLoop();
                    return new AttemptExecutionResult(
                            attemptNumber,
                            decision,
                            ClientUtil.retryAfterMillis(attempt.ctx().log())
                    );
                })
                .exceptionally(cause -> {
                    assert retryEventLoop.inEventLoop();
                    abort(cause);
                    return null;
                });
    }

    private RpcRetryAttempt newAttempt(int attemptNumber) {
        final boolean isInitialAttempt = attemptNumber <= 1;

        final ClientRequestContext attemptCtx = ClientUtil.newDerivedContext(ctx, null, req,
                                                                             isInitialAttempt);

        return new RpcRetryAttempt(config, retryEventLoop, attemptNumber, attemptCtx, req);
    }

    @Override
    public void commit(int attemptNumber) {
        checkState(retryEventLoop.inEventLoop());

        if (state == State.COMPLETED) {
            return;
        }
        checkState(state == State.PENDING);

        checkArgument(attemptNumber >= 1);
        checkArgument(attemptNumber <= attempts.size());

        final RpcRetryAttempt attemptToCommit = attempts.get(attemptNumber - 1);

        checkState(attemptToCommit != null);
        checkState(attemptToCommit.state() == RpcRetryAttempt.State.DECIDED);

        state = State.COMPLETED;

        abortAllExcept(attemptToCommit);

        final RpcResponse attemptRes = attemptToCommit.commit();

        ctx.logBuilder().endResponseWithChild(attemptToCommit.ctx().log());
        final HttpRequest attemptReq = attemptToCommit.ctx().request();
        if (attemptReq != null) {
            ctx.updateRequest(attemptReq);
        }
        resFuture.complete(attemptRes);
    }

    @Override
    public void abort(Throwable cause) {
        checkState(retryEventLoop.inEventLoop());

        if (state == State.COMPLETED) {
            return;
        }

        state = State.COMPLETED;

        abortAllExcept(null);

        if (!ctx.log().isRequestComplete()) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
        resFuture.completeExceptionally(cause);
    }

    @Override
    public void abort(int attemptNumber, Throwable cause) {
        checkState(retryEventLoop.inEventLoop());
        checkArgument(attemptNumber >= 1);

        final RpcRetryAttempt attemptToAbort;
        if (state == State.COMPLETED) {
            return;
        }

        checkState(state == State.PENDING);
        checkArgument(attemptNumber <= attempts.size());

        attemptToAbort = attempts.get(attemptNumber - 1);
        attemptToAbort.abort(cause);
    }

    private void abortAllExcept(@Nullable RpcRetryAttempt winningAttempt) {
        assert retryEventLoop.inEventLoop();
        assert state == State.COMPLETED : state;

        for (final RpcRetryAttempt attempt : attempts) {
            if (attempt == winningAttempt) {
                continue;
            }

            attempt.abort(AbortedAttemptException.get());
        }
    }

    @Override
    public RpcResponse res() {
        return res;
    }

    public long deadlineTimeNanos() {
        return deadlineTimeNanos;
    }
}
