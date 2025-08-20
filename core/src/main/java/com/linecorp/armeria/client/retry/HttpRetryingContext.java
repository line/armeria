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
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.client.AggregatedHttpRequestDuplicator;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.ClientUtil;

final class HttpRetryingContext implements RetryingContext<HttpRequest, HttpResponse, HttpRetryAttempt> {
    private static final Logger logger = LoggerFactory.getLogger(HttpRetryingContext.class);

    private enum State {
        UNINITIALIZED,
        INITIALIZING,
        INITIALIZED,
        COMPLETING,
        COMPLETED
    }

    private State state;
    private final ClientRequestContext ctx;
    private final RetryConfig<HttpResponse> retryConfig;
    private final HttpResponse res;
    private final CompletableFuture<HttpResponse> resFuture;
    private final HttpRequest req;
    @Nullable
    private HttpRequestDuplicator reqDuplicator;
    private final RetryCounter retryCounter;
    private final RetryScheduler scheduler;

    private final long deadlineTimeNanos;
    private final boolean hasDeadline;

    private final boolean useRetryAfter;
    List<HttpRetryAttempt> attemptsSoFar;

    HttpRetryingContext(ClientRequestContext ctx,
                        RetryConfig<HttpResponse> retryConfig,
                        HttpResponse res,
                        CompletableFuture<HttpResponse> resFuture,
                        HttpRequest req,
                        boolean useRetryAfter) {

        state = State.UNINITIALIZED;
        this.ctx = ctx;
        this.retryConfig = retryConfig;
        this.resFuture = resFuture;
        this.res = res;
        this.req = req;
        reqDuplicator = null; // will be initialized in init().
        retryCounter = new RetryCounter(retryConfig.maxTotalAttempts());
        scheduler = new RetryScheduler(ctx);

        final long responseTimeoutMillis = ctx.responseTimeoutMillis();
        if (responseTimeoutMillis <= 0 || responseTimeoutMillis == Long.MAX_VALUE) {
            deadlineTimeNanos = 0;
            hasDeadline = false;
        } else {
            deadlineTimeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
            hasDeadline = true;
        }

        this.useRetryAfter = useRetryAfter;
        attemptsSoFar = new LinkedList<>();
    }

    @Override
    public CompletableFuture<Boolean> init() {
        assert state == State.UNINITIALIZED;
        state = State.INITIALIZING;
        final CompletableFuture<Boolean> initFuture = new CompletableFuture<>();

        if (ctx.exchangeType().isRequestStreaming()) {
            reqDuplicator =
                    req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
            state = State.INITIALIZED;
            initFuture.complete(true);
        } else {
            req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
               .handle((agg, reqCause) -> {
                   assert state == State.INITIALIZING;

                   if (reqCause != null) {
                       resFuture.completeExceptionally(reqCause);
                       ctx.logBuilder().endRequest(reqCause);
                       ctx.logBuilder().endResponse(reqCause);
                       state = State.COMPLETED;
                       initFuture.complete(false);
                   } else {
                       reqDuplicator = new AggregatedHttpRequestDuplicator(agg);
                       state = State.INITIALIZED;
                       initFuture.complete(true);
                   }
                   return null;
               });
        }

        return initFuture;
    }

    @Override
    @Nullable
    public HttpRetryAttempt executeAttempt(@Nullable Backoff backoff,
                                           Client<HttpRequest, HttpResponse> delegate) {
        if (isCompleted()) {
            return null;
        }

        assert state == State.INITIALIZED;
        assert reqDuplicator != null;

        retryCounter.recordAttemptWith(backoff);

        final int attemptNumber = retryCounter.attemptNumber();
        final boolean isInitialAttempt = attemptNumber <= 1;

        if (!setResponseTimeout()) {
            throw ResponseTimeoutException.get();
        }

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

        final HttpResponse attemptRes;
        final ClientRequestContextExtension attemptCtxExt =
                attemptCtx.as(ClientRequestContextExtension.class);
        if (!isInitialAttempt && attemptCtxExt != null && attemptCtx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(attemptCtx);
            // if the endpoint hasn't been selected,
            // try to initialize the attempCtx with a new endpoint/event loop
            attemptRes = initContextAndExecuteWithFallback(
                    delegate, attemptCtxExt, HttpResponse::of,
                    (context, cause) ->
                            HttpResponse.ofFailure(cause), attemptReq, false);
        } else {
            attemptRes = executeWithFallback(delegate, attemptCtx,
                                             (context, cause) ->
                                                     HttpResponse.ofFailure(cause), attemptReq, false);
        }

        final HttpRetryAttempt attempt = new HttpRetryAttempt(this, attemptCtx, attemptRes,
                                                              ctx.exchangeType().isResponseStreaming());
        attemptsSoFar.add(attempt);
        return attempt;
    }

    // returns Long.MAX_VALUE if no retry is possible.
    @Override
    public long nextRetryTimeNanos(HttpRetryAttempt attempt, Backoff backoff) {
        if (state != State.INITIALIZED) {
            return Long.MAX_VALUE;
        }

        if (retryCounter.hasReachedMaxAttempts()) {
            logger.debug("Exceeded the default number of max attempt: {}", retryConfig.maxTotalAttempts());
            return Long.MAX_VALUE;
        }

        final long nextRetryDelayForBackoffMillis = backoff.nextDelayMillis(
                retryCounter.attemptNumberForBackoff(backoff) + 1);

        if (nextRetryDelayForBackoffMillis < 0) {
            logger.debug("Exceeded the number of max attempts in the backoff: {}", backoff);
            return Long.MAX_VALUE;
        }

        final long nextRetryDelayMillis = Math.max(nextRetryDelayForBackoffMillis,
                                                   useRetryAfter ? attempt.retryAfterMillis() : -1);
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
        scheduler.scheduleNextRetry(nextRetryTimeNanos, retryTask, exceptionHandler);
    }

    @Override
    public void commit(HttpRetryAttempt attemptToCommit) {
        if (state == State.COMPLETED) {
            // Already completed.
            return;
        }
        checkState(attemptToCommit.state() == HttpRetryAttempt.State.DECIDED);
        assert state == State.INITIALIZED;
        assert reqDuplicator != null;
        state = State.COMPLETED;

        for (final HttpRetryAttempt attempt : attemptsSoFar) {
            if (attempt != attemptToCommit) {
                // todo(szymon): check state.
                attempt.abort();
            }
        }

        final HttpResponse attemptRes = attemptToCommit.commit();
        // todo(szymon): replace with endResponseWithChild
        ctx.logBuilder().endResponseWithChild(attemptToCommit.ctx().log());
        resFuture.complete(attemptRes);
        reqDuplicator.close();
    }

    @Override
    public void abort(HttpRetryAttempt attempt) {
        assert state == State.INITIALIZED || state == State.COMPLETING || state == State.COMPLETED;
        attempt.abort();
    }

    @Override
    public void abort(Throwable cause) {
        if (state == State.COMPLETED) {
            // Already completed.
            return;
        }

        assert state == State.INITIALIZED || state == State.COMPLETING;
        assert reqDuplicator != null;
        state = State.COMPLETED;

        for (final HttpRetryAttempt attempt : attemptsSoFar) {
            // todo(szymon): check state.
            attempt.abort();
        }

        reqDuplicator.abort(cause);

        // todo(szymon): verify that this safe to do so we can avoid isInitialAttempt check
        if (!ctx.log().isRequestComplete()) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);

        resFuture.completeExceptionally(cause);
    }

    private boolean isCompleted() {
        if (state == State.COMPLETING || state == State.COMPLETED) {
            return true;
        }

        assert state == State.INITIALIZED;

        // The request or response has been aborted by the client before it receives a response,
        // so stop retrying.
        if (req.whenComplete().isCompletedExceptionally()) {
            state = State.COMPLETING;
            req.whenComplete().handle((unused, cause) -> {
                abort(cause);
                return null;
            });
            return true;
        }

        if (res.isComplete()) {
            state = State.COMPLETING;
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
            return true;
        }

        return false;
    }

    @Override
    public HttpResponse res() {
        return res;
    }

    RetryConfig<HttpResponse> config() {
        return retryConfig;
    }

    public boolean setResponseTimeout() {
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

        final long remainingTimeUntilDeadlineMillis = TimeUnit.NANOSECONDS.toMillis(
                deadlineTimeNanos - System.nanoTime());

        // Consider 0 or less than 0 of actualResponseTimeoutMillis as timed out.
        if (remainingTimeUntilDeadlineMillis <= 0) {
            return -1;
        }

        if (retryConfig.responseTimeoutMillisForEachAttempt() > 0) {
            return Math.min(retryConfig.responseTimeoutMillisForEachAttempt(),
                            remainingTimeUntilDeadlineMillis);
        }

        return remainingTimeUntilDeadlineMillis;
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("state", state)
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
