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
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.AggregatedHttpRequestDuplicator;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.ClientUtil;

final class HttpRetryingContext implements RetryingContext<HttpRequest, HttpResponse> {
    private static final Logger logger = LoggerFactory.getLogger(HttpRetryingContext.class);

    private enum State {
        UNINITIALIZED,
        INITIALIZING,
        INITIALIZED,
        EXECUTING,
        COMPLETED
    }

    private final Object lock = new Object();

    private State state;
    private final ClientRequestContext ctx;
    private final RetryConfig<HttpResponse> config;
    private final HttpResponse res;
    private final CompletableFuture<HttpResponse> resFuture;
    private final HttpRequest req;
    @Nullable
    private HttpRequestDuplicator reqDuplicator;
    private final RetryCounter counter;
    private final RetryScheduler scheduler;

    private final long deadlineTimeNanos;
    private final boolean hasDeadline;

    private final boolean useRetryAfter;
    @Nullable
    HttpRetryAttempt currentAttempt;

    HttpRetryingContext(ClientRequestContext ctx,
                        RetryConfig<HttpResponse> config,
                        HttpResponse res,
                        CompletableFuture<HttpResponse> resFuture,
                        HttpRequest req,
                        boolean useRetryAfter) {

        state = State.UNINITIALIZED;
        this.ctx = ctx;
        this.config = config;
        this.resFuture = resFuture;
        this.res = res;
        this.req = req;
        reqDuplicator = null; // will be initialized in init().
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

        this.useRetryAfter = useRetryAfter;
        currentAttempt = null;
    }

    @Override
    public CompletableFuture<Boolean> init() {
        synchronized (lock) {
            checkState(state == State.UNINITIALIZED);
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
                       synchronized (lock) {
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
                       }
                       return null;
                   });
            }

            initFuture.whenComplete((initSuccessful, initCause) -> {
                synchronized (lock) {
                    assert state == State.INITIALIZED || state == State.COMPLETED;
                    if (!initSuccessful || initCause != null) {
                        return;
                    }

                    req.whenComplete().handle((unused, cause) -> {
                        if (cause != null) {
                            abort(cause);
                        }
                        return null;
                    });

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
                }
            });

            return initFuture;
        }
    }

    @Override
    public CompletableFuture<@Nullable RetryDecision> executeAttempt(
            @Nullable Backoff backoff,
            Client<HttpRequest, HttpResponse> delegate) {
        synchronized (lock) {
            checkState(state == State.INITIALIZED);
            assert reqDuplicator != null;
            // We are not supporting concurrent attempts (yet).
            // As such, we expect the previous attempt (if any) to be aborted before
            // (abortion sets this field to null).
            // assert state != State.EXECUTING;
            assert currentAttempt == null;

            if (!setResponseTimeout()) {
                return UnmodifiableFuture.exceptionallyCompletedFuture(ResponseTimeoutException.get());
            }

            state = State.EXECUTING;
            counter.recordAttemptWith(backoff);

            final int attemptNumber = counter.attemptNumber();
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
            currentAttempt = attempt;
            return attempt.whenDecided();
        }
    }

    // returns Long.MAX_VALUE if no retry is possible.
    @Override
    public long nextRetryTimeNanos(Backoff backoff) {
        synchronized (lock) {
            checkState(state == State.EXECUTING);
            assert currentAttempt != null;
            checkState(currentAttempt.state() == HttpRetryAttempt.State.DECIDED);

            if (counter.hasReachedMaxAttempts()) {
                logger.debug("Exceeded the default number of max attempt: {}", config.maxTotalAttempts());
                return Long.MAX_VALUE;
            }

            final long nextRetryDelayForBackoffMillis = backoff.nextDelayMillis(
                    counter.attemptNumberForBackoff(backoff) + 1);

            if (nextRetryDelayForBackoffMillis < 0) {
                logger.debug("Exceeded the number of max attempts in the backoff: {}", backoff);
                return Long.MAX_VALUE;
            }

            final long nextRetryDelayMillis = Math.max(nextRetryDelayForBackoffMillis,
                                                       useRetryAfter ? currentAttempt.retryAfterMillis() : -1);
            final long nextDelayTimeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    nextRetryDelayMillis);

            if (hasDeadline && nextDelayTimeNanos > deadlineTimeNanos) {
                // The next retry will be after the response deadline. So return just Long.MAX_VALUE.
                return Long.MAX_VALUE;
            }

            return nextDelayTimeNanos;
        }
    }

    @Override
    public void scheduleNextRetry(long nextRetryTimeNanos, Runnable retryTask,
                                  Consumer<? super Throwable> exceptionHandler) {
        synchronized (lock) {
            checkState(state == State.INITIALIZED);
            assert currentAttempt == null;
            scheduler.scheduleNextRetry(nextRetryTimeNanos, retryTask, exceptionHandler);
        }
    }

    @Override
    public void commit() {
        synchronized (lock) {
            if (state == State.COMPLETED) {
                // Already completed.
                return;
            }
            checkState(state == State.EXECUTING);
            assert reqDuplicator != null;
            assert currentAttempt != null;
            checkState(currentAttempt.state() == HttpRetryAttempt.State.DECIDED);

            state = State.COMPLETED;

            final HttpResponse attemptRes = currentAttempt.commit();
            // todo(szymon): replace with endResponseWithChild
            ctx.logBuilder().endResponseWithChild(currentAttempt.ctx().log());
            resFuture.complete(attemptRes);
            reqDuplicator.close();
        }
    }

    @Override
    public void abortAttempt() {
        synchronized (lock) {
            checkState(state == State.EXECUTING);
            checkState(currentAttempt != null, "No active attempt to abort");
            currentAttempt.abort();
            state = State.INITIALIZED;
            currentAttempt = null;
        }
    }

    @Override
    public void abort(Throwable cause) {
        synchronized (lock) {
            if (state == State.COMPLETED) {
                // Already completed.
                return;
            }

            if (state == State.EXECUTING) {
                assert currentAttempt != null;
                currentAttempt.abort();
            }

            state = State.COMPLETED;

            if (reqDuplicator != null) {
                reqDuplicator.abort(cause);
            }

            if (!ctx.log().isRequestComplete()) {
                ctx.logBuilder().endRequest(cause);
            }
            ctx.logBuilder().endResponse(cause);

            resFuture.completeExceptionally(cause);
        }
    }

    @Override
    public HttpResponse res() {
        return res;
    }

    RetryConfig<HttpResponse> config() {
        return config;
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

        final long remainingTimeUntilDeadlineMillis = TimeUnit.NANOSECONDS.toMillis(
                deadlineTimeNanos - System.nanoTime());

        // Consider 0 or less than 0 of actualResponseTimeoutMillis as timed out.
        if (remainingTimeUntilDeadlineMillis <= 0) {
            return -1;
        }

        if (config.responseTimeoutMillisForEachAttempt() > 0) {
            return Math.min(config.responseTimeoutMillisForEachAttempt(),
                            remainingTimeUntilDeadlineMillis);
        }

        return remainingTimeUntilDeadlineMillis;
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return MoreObjects
                    .toStringHelper(this)
                    .add("state", state)
                    .add("ctx", ctx)
                    .add("config", config)
                    .add("req", req)
                    .add("res", res)
                    .add("useRetryAfter", useRetryAfter)
                    .add("deadlineTimeNanos", deadlineTimeNanos)
                    .add("hasDeadline", hasDeadline)
                    .add("counter", counter)
                    .add("scheduler", scheduler)
                    .add("currentAttempt", currentAttempt)
                    .toString();
        }
    }
}
