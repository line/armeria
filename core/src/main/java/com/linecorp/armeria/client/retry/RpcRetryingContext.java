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

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.ClientUtil;

import io.netty.util.concurrent.ScheduledFuture;

final class RpcRetryingContext implements RetryingContext<RpcRequest, RpcResponse, RpcRetryAttempt> {
    private static final Logger logger = LoggerFactory.getLogger(RpcRetryingContext.class);

    private enum State {
        INITIALIZED,
        COMPLETED
    }

    private State state;
    private final ClientRequestContext ctx;
    private final RetryConfig<RpcResponse> retryConfig;
    private final CompletableFuture<RpcResponse> resFuture;
    private final RpcResponse res;
    private final RpcRequest req;
    private final RetryCounter retryCounter;

    private final long deadlineTimeNanos;
    private final boolean hasDeadline;

    List<RpcRetryAttempt> attemptsSoFar;

    RpcRetryingContext(ClientRequestContext ctx,
                       RetryConfig<RpcResponse> retryConfig,
                       CompletableFuture<RpcResponse> resFuture,
                       RpcResponse res,
                       RpcRequest req) {
        state = State.INITIALIZED;
        this.ctx = ctx;
        this.retryConfig = retryConfig;
        this.resFuture = resFuture;
        this.res = res;
        this.req = req;
        retryCounter = new RetryCounter(retryConfig.maxTotalAttempts());

        final long responseTimeoutMillis = ctx.responseTimeoutMillis();
        if (responseTimeoutMillis <= 0 || responseTimeoutMillis == Long.MAX_VALUE) {
            deadlineTimeNanos = 0;
            hasDeadline = false;
        } else {
            deadlineTimeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
            hasDeadline = true;
        }

        attemptsSoFar = new LinkedList<>();
    }

    @Override
    public CompletableFuture<Boolean> init() {
        assert state == State.INITIALIZED;
        // Nothing special to initialize for RPC retrying.
        return UnmodifiableFuture.completedFuture(true);
    }

    @Override
    @Nullable
    public RpcRetryAttempt executeAttempt(@Nullable Backoff backoff,
                                          Client<RpcRequest, RpcResponse> delegate) {
        if (res.isDone()) {
            return null;
        }

        assert state == State.INITIALIZED;

        retryCounter.recordAttemptWith(backoff);

        final int attemptNumber = retryCounter.attemptNumber();
        final boolean isInitialAttempt = attemptNumber <= 1;

        if (!setResponseTimeout()) {
            throw ResponseTimeoutException.get();
        }

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
        attemptsSoFar.add(attempt);
        return attempt;
    }

    @Override
    public long nextRetryTimeNanos(RpcRetryAttempt attempt, Backoff backoff) {
        if (state != State.INITIALIZED) {
            return Long.MAX_VALUE;
        }

        if (retryCounter.hasReachedMaxAttempts()) {
            logger.debug("Exceeded the default number of max attempt: {}", retryConfig.maxTotalAttempts());
            return Long.MAX_VALUE;
        }

        final long nextRetryDelayMillis = backoff.nextDelayMillis(
                retryCounter.attemptNumberForBackoff(backoff) + 1);

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
        try {
            final long nextRetryDelayMillis = TimeUnit.NANOSECONDS.toMillis(
                    nextRetryTimeNanos - System.nanoTime());
            if (nextRetryDelayMillis <= 0) {
                ctx.eventLoop().execute(retryTask);
            } else {
                @SuppressWarnings("unchecked")
                final ScheduledFuture<Void> scheduledFuture = (ScheduledFuture<Void>) ctx
                        .eventLoop().schedule(retryTask, nextRetryDelayMillis, TimeUnit.MILLISECONDS);
                scheduledFuture.addListener(future -> {
                    if (future.isCancelled()) {
                        exceptionHandler.accept(new IllegalStateException(
                                ClientFactory.class.getSimpleName() + " has been closed."));
                    } else if (future.cause() != null) {
                        exceptionHandler.accept(future.cause());
                    }
                });
            }
        } catch (Throwable t) {
            exceptionHandler.accept(t);
        }
    }

    @Override
    public void commit(RpcRetryAttempt attemptToCommit) {
        if (state == State.COMPLETED) {
            // Already completed, so just return.
            return;
        }
        checkState(attemptToCommit.state() == RpcRetryAttempt.State.DECIDED);
        assert state == State.INITIALIZED;
        state = State.COMPLETED;

        for (final RpcRetryAttempt attempt : attemptsSoFar) {
            if (attempt != attemptToCommit) {
                // todo(szymon): check state.
                attempt.abort();
            }
        }

        final RpcResponse attemptRes = attemptToCommit.commit();

        ctx.logBuilder().endResponseWithChild(attemptToCommit.ctx().log());
        final HttpRequest attemptReq = attemptToCommit.ctx().request();
        if (attemptReq != null) {
            ctx.updateRequest(attemptReq);
        }
        resFuture.complete(attemptRes);
    }

    @Override
    public void abort(RpcRetryAttempt attempt) {
        // Can be called in any state.
        attempt.abort();
    }

    @Override
    public void abort(Throwable cause) {
        if (state == State.COMPLETED) {
            return;
        }

        assert state == State.INITIALIZED;
        state = State.COMPLETED;

        for (final RpcRetryAttempt attempt : attemptsSoFar) {
            // todo(szymon): check state.
            attempt.abort();
        }

        // todo(szymon): verify that this safe to do so we can avoid isInitialAttempt check
        if (!ctx.log().isRequestComplete()) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);

        resFuture.completeExceptionally(cause);
    }

    @Override
    public RpcResponse res() {
        return res;
    }

    RetryConfig<RpcResponse> config() {
        return retryConfig;
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
            return retryConfig.responseTimeoutMillisForEachAttempt();
        }

        final long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineTimeNanos - System.nanoTime());
        if (remaining <= 0) {
            return -1;
        }
        if (retryConfig.responseTimeoutMillisForEachAttempt() > 0) {
            return Math.min(retryConfig.responseTimeoutMillisForEachAttempt(), remaining);
        }
        return remaining;
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("ctx", ctx)
                .add("retryConfig", retryConfig)
                .add("req", req)
                .add("res", res)
                .add("deadlineTimeNanos", deadlineTimeNanos)
                .add("hasDeadline", hasDeadline)
                .add("retryCounter", retryCounter)
                .toString();
    }
}
