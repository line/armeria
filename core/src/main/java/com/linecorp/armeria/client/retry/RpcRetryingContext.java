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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.ClientUtil;

final class RpcRetryingContext implements RetryingContext<RpcRequest, RpcResponse> {
    private static final Logger logger = LoggerFactory.getLogger(RpcRetryingContext.class);

    private enum State {
        INITIALIZED,
        EXECUTING,
        COMPLETED
    }

    private State state;
    private final ClientRequestContext ctx;
    private final RetryConfig<RpcResponse> config;
    private final CompletableFuture<RpcResponse> resFuture;
    private final RpcResponse res;
    private final RpcRequest req;
    private final RetryCounter counter;
    private final RetryScheduler scheduler;

    private final long deadlineTimeNanos;
    private final boolean hasDeadline;

    @Nullable
    RpcRetryAttempt currentAttempt;

    RpcRetryingContext(ClientRequestContext ctx,
                       RetryConfig<RpcResponse> config,
                       CompletableFuture<RpcResponse> resFuture,
                       RpcResponse res,
                       RpcRequest req) {
        state = State.INITIALIZED;
        this.ctx = ctx;
        this.config = config;
        this.resFuture = resFuture;
        this.res = res;
        this.req = req;
        counter = new RetryCounter(config.maxTotalAttempts());
        scheduler = new RetryScheduler(ctx);

        final long responseTimeoutMillis = ctx.responseTimeoutMillis();
        if (responseTimeoutMillis <= 0 || responseTimeoutMillis == Long.MAX_VALUE) {
            deadlineTimeNanos = 0;
            hasDeadline = false;
        } else {
            deadlineTimeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
            hasDeadline = true;
        }

        currentAttempt = null;
    }

    @Override
    public CompletableFuture<Boolean> init() {
        checkState(state == State.INITIALIZED);

        res.whenComplete().handle((result, cause) -> {
            final Throwable abortCause;
            if (cause != null) {
                abortCause = cause;
            } else {
                abortCause = AbortedStreamException.get();
            }
            abort(abortCause);
            return null;
        });

        return UnmodifiableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<@Nullable RetryDecision> executeAttempt(@Nullable Backoff backoff,
                                                                     Client<RpcRequest, RpcResponse> delegate) {
        checkState(state == State.INITIALIZED);
        assert currentAttempt == null;

        if (!setResponseTimeout()) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(ResponseTimeoutException.get());
        }

        state = State.EXECUTING;

        counter.recordAttemptWith(backoff);

        final int attemptNumber = counter.attemptNumber();
        final boolean isInitialAttempt = attemptNumber <= 1;

        final ClientRequestContext attemptCtx = ClientUtil.newDerivedContext(ctx, null, req, isInitialAttempt);

        if (!isInitialAttempt) {
            attemptCtx.mutateAdditionalRequestHeaders(
                    mutator -> mutator.add(ARMERIA_RETRY_COUNT, Integer.toString(attemptNumber - 1)));
        }

        final RpcResponse attemptRes;
        final ClientRequestContextExtension attemptCtxExtension =
                attemptCtx.as(ClientRequestContextExtension.class);
        final EndpointGroup endpointGroup = attemptCtx.endpointGroup();
        if (!isInitialAttempt && attemptCtxExtension != null &&
            endpointGroup != null && attemptCtx.endpoint() == null) {
            // Clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(attemptCtx);
            // Initialize the context with a new endpoint/event loop if not selected yet
            attemptRes = initContextAndExecuteWithFallback(delegate, attemptCtxExtension, RpcResponse::from,
                                                           (unused, cause) -> RpcResponse.ofFailure(cause),
                                                           req, true);
        } else {
            attemptRes = executeWithFallback(delegate, attemptCtx,
                                             (unused, cause) -> RpcResponse.ofFailure(cause),
                                             req, true);
        }

        final RpcRetryAttempt attempt = new RpcRetryAttempt(this, attemptCtx, attemptRes);
        currentAttempt = attempt;
        return attempt.whenDecided();
    }

    @Override
    public long nextRetryTimeNanos(Backoff backoff) {
        if (state != State.EXECUTING) {
            return Long.MAX_VALUE;
        }

        if (counter.hasReachedMaxAttempts()) {
            logger.debug("Exceeded the default number of max attempt: {}", config.maxTotalAttempts());
            return Long.MAX_VALUE;
        }

        final long nextRetryDelayMillis = backoff.nextDelayMillis(
                counter.attemptNumberForBackoff(backoff) + 1);

        if (nextRetryDelayMillis < 0) {
            logger.debug("Exceeded the number of max attempts in the backoff: {}", backoff);
            return Long.MAX_VALUE;
        }

        final long nextDelayTimeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(nextRetryDelayMillis);
        if (hasDeadline && nextDelayTimeNanos > deadlineTimeNanos) {
            // The next retry will be after the response deadline. So return just Long.MAX_VALUE.
            return Long.MAX_VALUE;
        }
        return nextDelayTimeNanos;
    }

    @Override
    public void scheduleNextRetry(long nextRetryTimeNanos, Runnable retryTask,
                                  Consumer<? super Throwable> exceptionHandler) {
        checkState(state == State.INITIALIZED);
        assert currentAttempt == null;
        scheduler.scheduleNextRetry(nextRetryTimeNanos, retryTask, exceptionHandler);
    }

    @Override
    public void commit() {
        if (state == State.COMPLETED) {
            // Already completed, so just return.
            return;
        }
        checkState(state == State.EXECUTING);
        assert currentAttempt != null;
        checkState(currentAttempt.state() == RpcRetryAttempt.State.DECIDED);

        state = State.COMPLETED;

        final RpcResponse attemptRes = currentAttempt.commit();
        ctx.logBuilder().endResponseWithChild(currentAttempt.ctx().log());
        final HttpRequest attemptReq = currentAttempt.ctx().request();
        if (attemptReq != null) {
            ctx.updateRequest(attemptReq);
        }
        resFuture.complete(attemptRes);
    }

    @Override
    public void abortAttempt() {
        checkState(state == State.EXECUTING);
        assert currentAttempt != null;
        // Can be called in any state.
        currentAttempt.abort();
        currentAttempt = null;
        state = State.INITIALIZED;
    }

    @Override
    public void abort(Throwable cause) {
        if (state == State.COMPLETED) {
            return;
        }

        if (state == State.EXECUTING) {
            assert currentAttempt != null;
            currentAttempt.abort();
        }

        state = State.COMPLETED;

        if (!ctx.log().isRequestComplete()) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
        resFuture.completeExceptionally(cause);
    }

    private boolean setResponseTimeout() {
        final long responseTimeoutMillis = responseTimeoutMillis();
        if (responseTimeoutMillis < 0) {
            return false;
        } else if (responseTimeoutMillis == 0) {
            ctx.clearResponseTimeout();
            return true;
        } else {
            ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, responseTimeoutMillis);
            return true;
        }
    }

    private long responseTimeoutMillis() {
        if (!hasDeadline) {
            return config.responseTimeoutMillisForEachAttempt();
        }

        final long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineTimeNanos - System.nanoTime());
        if (remaining <= 0) {
            return -1;
        }
        if (config.responseTimeoutMillisForEachAttempt() > 0) {
            return Math.min(config.responseTimeoutMillisForEachAttempt(), remaining);
        }
        return remaining;
    }

    @Override
    public RpcResponse res() {
        return res;
    }

    RetryConfig<RpcResponse> config() {
        return config;
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("ctx", ctx)
                .add("retryConfig", config)
                .add("req", req)
                .add("res", res)
                .add("deadlineTimeNanos", deadlineTimeNanos)
                .add("hasDeadline", hasDeadline)
                .add("counter", counter)
                .add("scheduler", scheduler)
                .add("currentAttempt", currentAttempt)
                .toString();
    }
}
