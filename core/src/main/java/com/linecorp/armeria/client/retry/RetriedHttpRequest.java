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
import static com.linecorp.armeria.client.retry.AbstractRetryingClient.ARMERIA_RETRY_COUNT;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.ContextAwareEventLoop;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.AggregatedHttpRequestDuplicator;
import com.linecorp.armeria.internal.client.ClientUtil;

class RetriedHttpRequest implements RetriedRequest<HttpRequest, HttpResponse> {
    private enum State {
        /**
         * Initial state. {@link RetriedHttpRequest} is instantiated but not yet initialized.
         */
        IDLE,
        /**
         *  {@link RetriedHttpRequest} is being initialized in {@link #init()} with the first call
         *  to {@link #executeAttempt(Client)}. See the javadoc of {@link #init()} for details.
         */
        INITIALIZING,
        /**
         * {@link RetriedHttpRequest} is initialized and is ready to execute/ is executing attempts.
         */
        PENDING,
        /**
         * Terminal state. Either {@link #commit(int)} or {@link #abort(Throwable)} was called.
         * {@link RetriedHttpRequest} will not execute any more attempts in that it completes every call
         * to {@link #executeAttempt(Client)} exceptionally with an {@link AbortedAttemptException}.
         * {@link #completedFuture} is completed.
         */
        COMPLETED
    }

    private final ContextAwareEventLoop retryEventLoop;
    private final RetryConfig<HttpResponse> config;
    private final ClientRequestContext ctx;
    private final HttpRequest req;

    private final long deadlineTimeNanos;
    private final boolean useRetryAfter;
    private final CompletableFuture<HttpResponse> completedFuture;

    private State state;

    /**
     * Future that completes when the initialization is done. It always completes successfully.
     */
    private final CompletableFuture<@Nullable Void> initFuture;

    int previousAttemptNumber;

    /**
     * Duplicator for the original request. It is set from {@code State.PENDING} onwards.
     */
    @Nullable
    private HttpRequestDuplicator reqDuplicator;

    final ArrayList<HttpRetryAttempt> attempts;

    RetriedHttpRequest(
            ContextAwareEventLoop retryEventLoop,
            RetryConfig<HttpResponse> config,
            ClientRequestContext ctx,
            HttpRequest req,
            long deadlineTimeNanos,
            boolean useRetryAfter
    ) {
        this.retryEventLoop = retryEventLoop;
        this.ctx = ctx;
        this.req = req;
        this.config = config;
        this.useRetryAfter = useRetryAfter;
        this.deadlineTimeNanos = deadlineTimeNanos;

        completedFuture = new CompletableFuture<>();
        initFuture = new CompletableFuture<>();

        state = State.IDLE;
        previousAttemptNumber = 0;
        // We can assume that most of the time we will succeed with the first attempt.
        attempts = new ArrayList<>(1);
        reqDuplicator = null;
    }

    @Override
    public CompletionStage<AttemptExecutionResult> executeAttempt(
            Client<HttpRequest, HttpResponse> delegate) {
        checkState(ctx.eventLoop().inEventLoop());
        return init().thenCompose(unused -> executeAttemptAfterInit(delegate));
    }

    /**
     * Initializes the {@link RetriedHttpRequest}. This method is idempotent.
     * Initialization includes
     *      - subscribing to {@link #req} to initiate abortion in case its fails,
     *      - building and setting {@link #reqDuplicator}.
     *
     * @return a future that completes when the initialization is done. After the future completes
     *         the {@link RetriedHttpRequest} is either in {@code PENDING} or {@code COMPLETED} state.
     * */
    private CompletableFuture<Void> init() {
        assert ctx.eventLoop().inEventLoop();

        if (state == State.INITIALIZING || state == State.PENDING || state == State.COMPLETED) {
            return initFuture;
        }

        assert state == State.IDLE : state;
        state = State.INITIALIZING;

        // We are not putting this in the constructor as we might get aborted before
        // we are properly initialized.
        req.whenComplete().handle((unused, cause) -> {
            if (cause != null) {
                if (retryEventLoop.inEventLoop()) {
                    abort(cause);
                } else {
                    retryEventLoop.execute(() -> abort(cause));
                }
            }
            return null;
        });

        // Guaranteed to be completed on the retryEventLoop.
        final CompletableFuture<HttpRequestDuplicator> reqDuplicatorFuture;

        if (ctx.exchangeType().isRequestStreaming()) {
            reqDuplicatorFuture = UnmodifiableFuture.completedFuture(
                    req.toDuplicator(retryEventLoop.withoutContext(), 0));
        } else {
            reqDuplicatorFuture = req.aggregate(
                                             // TODO: Do we need to run this with the context?
                                             AggregationOptions.usePooledObjects(ctx.alloc(), retryEventLoop))
                                     .thenApply(AggregatedHttpRequestDuplicator::new);
        }

        reqDuplicatorFuture
                .thenAccept(
                        reqDuplicator0 -> {
                            assert retryEventLoop.inEventLoop();
                            assert reqDuplicator == null : reqDuplicator;

                            if (state == State.COMPLETED) {
                                reqDuplicator0.close();
                            } else {
                                assert state == State.INITIALIZING : state;
                                reqDuplicator = reqDuplicator0;
                                state = State.PENDING;
                            }
                        }
                )
                .exceptionally(cause -> {
                    abort(cause);
                    return null;
                })
                .thenRun(() -> initFuture.complete(null));

        return initFuture;
    }

    private CompletableFuture<AttemptExecutionResult> executeAttemptAfterInit(
            Client<HttpRequest, HttpResponse> delegate) {
        assert retryEventLoop.inEventLoop();

        if (state == State.COMPLETED) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(AbortedAttemptException.get());
        }

        // We need to be initialized/ the reqDuplicator must be present.
        checkState(state == State.PENDING);
        assert reqDuplicator != null;
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

        final HttpRetryAttempt attempt = newAttempt(attemptNumber);
        attempts.add(attempt);
        return attempt
                .executeAndDecide(delegate)
                .thenApply(decision -> {
                    assert retryEventLoop.inEventLoop();
                    return new AttemptExecutionResult(
                            attemptNumber,
                            decision,
                            useRetryAfter ?
                            ClientUtil.retryAfterMillis(attempt.ctx().log())
                                          : -1L
                    );
                })
                .exceptionally(cause -> {
                    assert retryEventLoop.inEventLoop();
                    abort(cause);
                    return null;
                });
    }

    private HttpRetryAttempt newAttempt(int attemptNumber) {
        assert reqDuplicator != null;

        final boolean isInitialAttempt = attemptNumber <= 1;

        final HttpRequest attemptReq;
        final ClientRequestContext attemptCtx;
        if (isInitialAttempt) {
            attemptReq = reqDuplicator.duplicate();
        } else {
            final RequestHeadersBuilder attemptHeadersBuilder = req.headers().toBuilder();
            attemptHeadersBuilder.setInt(ARMERIA_RETRY_COUNT, attemptNumber - 1);
            attemptReq = reqDuplicator.duplicate(attemptHeadersBuilder.build());
        }

        attemptCtx = ClientUtil.newDerivedContext(ctx, attemptReq, ctx.rpcRequest(), isInitialAttempt);

        return new HttpRetryAttempt(config, retryEventLoop, attemptNumber, attemptCtx, attemptReq);
    }

    @Override
    public void commit(int attemptNumber) {
        checkState(retryEventLoop.inEventLoop());

        if (state == State.COMPLETED) {
            return;
        }
        checkState(state == State.PENDING);
        assert reqDuplicator != null;
        assert !completedFuture.isDone();

        checkArgument(attemptNumber >= 1);
        checkArgument(attemptNumber <= attempts.size());

        final HttpRetryAttempt attemptToCommit = attempts.get(attemptNumber - 1);

        checkState(attemptToCommit.state() == HttpRetryAttempt.State.DECIDED);

        state = State.COMPLETED;

        abortAllExcept(attemptToCommit);

        final HttpResponse res = attemptToCommit.commit();

        reqDuplicator.close();
        ctx.logBuilder().endResponseWithChild(attemptToCommit.ctx().log());

        completedFuture.complete(res);
    }

    @Override
    public void abort(Throwable cause) {
        checkState(retryEventLoop.inEventLoop());

        if (state == State.COMPLETED) {
            return;
        }
        assert !completedFuture.isDone();

        state = State.COMPLETED;

        abortAllExcept(null);

        if (reqDuplicator != null) {
            reqDuplicator.close();
        }

        if (!ctx.log().isRequestComplete()) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
        completedFuture.completeExceptionally(cause);
    }

    @Override
    public void abort(int attemptNumber, Throwable cause) {
        checkState(retryEventLoop.inEventLoop());
        checkArgument(attemptNumber >= 1);

        final HttpRetryAttempt attemptToAbort;
        if (state == State.COMPLETED) {
            return;
        }

        checkState(state == State.PENDING);
        checkArgument(attemptNumber <= attempts.size());

        attemptToAbort = attempts.get(attemptNumber - 1);
        attemptToAbort.abort(cause);
    }

    private void abortAllExcept(@Nullable HttpRetryAttempt winningAttempt) {
        assert retryEventLoop.inEventLoop();
        assert state == State.COMPLETED : state;

        for (final HttpRetryAttempt attempt : attempts) {
            if (attempt == winningAttempt) {
                continue;
            }

            attempt.abort(AbortedAttemptException.get());
        }
    }

    @Override
    public CompletableFuture<HttpResponse> whenComplete() {
        return completedFuture;
    }
}
