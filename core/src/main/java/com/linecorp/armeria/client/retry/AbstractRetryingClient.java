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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * A {@link Client} decorator that handles failures of remote invocation and retries requests.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractRetryingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRetryingClient.class);

    /**
     * The header which indicates the retry count of a {@link Request}.
     * The server might use this value to reject excessive retries, etc.
     */
    public static final AsciiString ARMERIA_RETRY_COUNT = HttpHeaderNames.of("armeria-retry-count");

    private static final AttributeKey<State> STATE =
            AttributeKey.valueOf(AbstractRetryingClient.class, "STATE");

    @Nullable
    private final RetryStrategy retryStrategy;

    @Nullable
    private final RetryStrategyWithContent<O> retryStrategyWithContent;

    private final int maxTotalAttempts;
    private final long responseTimeoutMillisForEachAttempt;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    protected AbstractRetryingClient(Client<I, O> delegate, RetryStrategy retryStrategy,
                                     int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        this(delegate, requireNonNull(retryStrategy, "retryStrategyWithoutContent"), null,
             maxTotalAttempts, responseTimeoutMillisForEachAttempt);
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    protected AbstractRetryingClient(Client<I, O> delegate,
                                     RetryStrategyWithContent<O> retryStrategyWithContent,
                                     int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        this(delegate, null, requireNonNull(retryStrategyWithContent, "retryStrategyWithContent"),
             maxTotalAttempts, responseTimeoutMillisForEachAttempt);
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    private AbstractRetryingClient(Client<I, O> delegate, @Nullable RetryStrategy retryStrategy,
                                   @Nullable RetryStrategyWithContent<O> retryStrategyWithContent,
                                   int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        super(delegate);
        this.retryStrategy = retryStrategy;
        this.retryStrategyWithContent = retryStrategyWithContent;

        checkArgument(maxTotalAttempts > 0, "maxTotalAttempts: %s (expected: > 0)", maxTotalAttempts);
        this.maxTotalAttempts = maxTotalAttempts;

        checkArgument(responseTimeoutMillisForEachAttempt >= 0,
                      "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                      responseTimeoutMillisForEachAttempt);
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        final State state =
                new State(maxTotalAttempts, responseTimeoutMillisForEachAttempt, ctx.responseTimeoutMillis());
        ctx.setAttr(STATE, state);
        return doExecute(ctx, req);
    }

    /**
     * Invoked by {@link #execute(ClientRequestContext, Request)}
     * after the deadline for response timeout is set.
     */
    protected abstract O doExecute(ClientRequestContext ctx, I req) throws Exception;

    /**
     * This should be called when retrying is finished.
     */
    protected static void onRetryingComplete(ClientRequestContext ctx) {
        ctx.logBuilder().endResponseWithLastChild();
    }

    /**
     * Returns the {@link RetryStrategy}.
     *
     * @throws IllegalStateException if the {@link RetryStrategy} is not set
     */
    protected RetryStrategy retryStrategy() {
        checkState(retryStrategy != null, "retryStrategy is not set.");
        return retryStrategy;
    }

    /**
     * Returns the {@link RetryStrategyWithContent}.
     *
     * @throws IllegalStateException if the {@link RetryStrategyWithContent} is not set
     */
    protected RetryStrategyWithContent<O> retryStrategyWithContent() {
        checkState(retryStrategyWithContent != null, "retryStrategyWithContent is not set.");
        return retryStrategyWithContent;
    }

    /**
     * Schedules next retry.
     */
    protected static void scheduleNextRetry(ClientRequestContext ctx,
                                            Consumer<? super Throwable> actionOnException,
                                            Runnable retryTask, long nextDelayMillis) {
        try {
            if (nextDelayMillis == 0) {
                ctx.contextAwareEventLoop().execute(retryTask);
            } else {
                @SuppressWarnings("unchecked")
                final ScheduledFuture<Void> scheduledFuture = (ScheduledFuture<Void>) ctx
                        .contextAwareEventLoop().schedule(retryTask, nextDelayMillis, TimeUnit.MILLISECONDS);
                scheduledFuture.addListener(future -> {
                    if (future.isCancelled()) {
                        // future is cancelled when the client factory is closed.
                        actionOnException.accept(new IllegalStateException(
                                ClientFactory.class.getSimpleName() + " has been closed."));
                    }
                });
            }
        } catch (Throwable t) {
            actionOnException.accept(t);
        }
    }

    /**
     * Resets the {@link ClientRequestContext#responseTimeoutMillis()}.
     *
     * @return {@code true} if the response timeout is set, {@code false} if it can't be set due to the timeout
     */
    @SuppressWarnings("MethodMayBeStatic") // Intentionally left non-static for better user experience.
    protected final boolean setResponseTimeout(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        final long responseTimeoutMillis = ctx.attr(STATE).responseTimeoutMillis();
        if (responseTimeoutMillis < 0) {
            return false;
        } else if (responseTimeoutMillis == 0) {
            ctx.clearResponseTimeout();
            return true;
        } else {
            ctx.setResponseTimeoutAfterMillis(responseTimeoutMillis);
            return true;
        }
    }

    /**
     * Returns the next delay which retry will be made after. The delay will be:
     *
     * <p>{@code Math.min(responseTimeoutMillis, Backoff.nextDelayMillis(int))}
     *
     * @return the number of milliseconds to wait for before attempting a retry. -1 if the
     *         {@code currentAttemptNo} exceeds the {@code maxAttempts} or the {@code nextDelay} is after
     *         the moment which timeout happens.
     */
    protected final long getNextDelay(ClientRequestContext ctx, Backoff backoff) {
        return getNextDelay(ctx, backoff, -1);
    }

    /**
     * Returns the next delay which retry will be made after. The delay will be:
     *
     * <p>{@code Math.min(responseTimeoutMillis, Math.max(Backoff.nextDelayMillis(int),
     * millisAfterFromServer))}
     *
     * @return the number of milliseconds to wait for before attempting a retry. -1 if the
     *         {@code currentAttemptNo} exceeds the {@code maxAttempts} or the {@code nextDelay} is after
     *         the moment which timeout happens.
     */
    @SuppressWarnings("MethodMayBeStatic") // Intentionally left non-static for better user experience.
    protected final long getNextDelay(ClientRequestContext ctx, Backoff backoff, long millisAfterFromServer) {
        requireNonNull(ctx, "ctx");
        requireNonNull(backoff, "backoff");
        final State state = ctx.attr(STATE);
        final int currentAttemptNo = state.currentAttemptNoWith(backoff);

        if (currentAttemptNo < 0) {
            logger.debug("Exceeded the default number of max attempt: {}", state.maxTotalAttempts);
            return -1;
        }

        long nextDelay = backoff.nextDelayMillis(currentAttemptNo);
        if (nextDelay < 0) {
            logger.debug("Exceeded the number of max attempts in the backoff: {}", backoff);
            return -1;
        }

        nextDelay = Math.max(nextDelay, millisAfterFromServer);
        if (state.timeoutForWholeRetryEnabled() && nextDelay > state.actualResponseTimeoutMillis()) {
            // The nextDelay will be after the moment which timeout will happen. So return just -1.
            return -1;
        }

        return nextDelay;
    }

    /**
     * Returns the total number of attempts of the current request represented by the specified
     * {@link ClientRequestContext}.
     */
    protected static int getTotalAttempts(ClientRequestContext ctx) {
        final State state = ctx.attr(STATE);
        if (state == null) {
            return 0;
        }
        return state.totalAttemptNo;
    }

    /**
     * Creates a new derived {@link ClientRequestContext}, replacing the requests.
     * If {@link ClientRequestContext#endpointSelector()} exists, a new {@link Endpoint} will be selected.
     */
    protected static ClientRequestContext newDerivedContext(ClientRequestContext ctx,
                                                            @Nullable HttpRequest req,
                                                            @Nullable RpcRequest rpcReq,
                                                            boolean initialAttempt) {
        final RequestId id = ctx.options().requestIdGenerator().get();
        final EndpointSelector endpointSelector = ctx.endpointSelector();
        if (endpointSelector != null && !initialAttempt) {
            return ctx.newDerivedContext(id, req, rpcReq, endpointSelector.select(ctx));
        } else {
            return ctx.newDerivedContext(id, req, rpcReq);
        }
    }

    private static class State {

        private final int maxTotalAttempts;
        private final long responseTimeoutMillisForEachAttempt;
        private final long deadlineNanos;

        @Nullable
        private Backoff lastBackoff;
        private int currentAttemptNoWithLastBackoff;
        private int totalAttemptNo;

        State(int maxTotalAttempts, long responseTimeoutMillisForEachAttempt, long responseTimeoutMillis) {
            this.maxTotalAttempts = maxTotalAttempts;
            this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;

            if (responseTimeoutMillis <= 0 || responseTimeoutMillis == Long.MAX_VALUE) {
                deadlineNanos = 0;
            } else {
                deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
            }
            totalAttemptNo = 1;
        }

        /**
         * Returns the smaller value between {@link #responseTimeoutMillisForEachAttempt} and
         * remaining {@link #responseTimeoutMillis}.
         *
         * @return 0 if the response timeout for both of each request and whole retry is disabled or
         *         -1 if the elapsed time from the first request has passed {@code responseTimeoutMillis}
         */
        long responseTimeoutMillis() {
            if (!timeoutForWholeRetryEnabled()) {
                return responseTimeoutMillisForEachAttempt;
            }

            final long actualResponseTimeoutMillis = actualResponseTimeoutMillis();

            // Consider 0 or less than 0 of actualResponseTimeoutMillis as timed out.
            if (actualResponseTimeoutMillis <= 0) {
                return -1;
            }

            if (responseTimeoutMillisForEachAttempt > 0) {
                return Math.min(responseTimeoutMillisForEachAttempt, actualResponseTimeoutMillis);
            }

            return actualResponseTimeoutMillis;
        }

        boolean timeoutForWholeRetryEnabled() {
            return deadlineNanos != 0;
        }

        long actualResponseTimeoutMillis() {
            return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        }

        int currentAttemptNoWith(Backoff backoff) {
            if (totalAttemptNo++ >= maxTotalAttempts) {
                return -1;
            }
            if (lastBackoff != backoff) {
                lastBackoff = backoff;
                currentAttemptNoWithLastBackoff = 1;
            }
            return currentAttemptNoWithLastBackoff++;
        }
    }
}
