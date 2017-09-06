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
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

import io.netty.util.AttributeKey;

/**
 * A {@link Client} decorator that handles failures of remote invocation and retries requests.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class RetryingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private static final AttributeKey<State> STATE =
            AttributeKey.valueOf(RetryingClient.class, "STATE");

    private final RetryStrategy<I, O> retryStrategy;
    private final int defaultMaxAttempts;
    private final long responseTimeoutMillisForEachAttempt;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    protected RetryingClient(Client<I, O> delegate, RetryStrategy<I, O> retryStrategy,
                             int defaultMaxAttempts, long responseTimeoutMillisForEachAttempt) {
        super(delegate);
        this.retryStrategy = requireNonNull(retryStrategy, "retryStrategy");
        checkArgument(defaultMaxAttempts > 0, "defaultMaxAttempts: %s (expected: > 0)", defaultMaxAttempts);
        this.defaultMaxAttempts = defaultMaxAttempts;

        checkArgument(responseTimeoutMillisForEachAttempt >= 0,
                      "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                      responseTimeoutMillisForEachAttempt);
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        final State state =
                new State(defaultMaxAttempts, responseTimeoutMillisForEachAttempt, ctx.responseTimeoutMillis());
        ctx.attr(STATE).set(state);
        return doExecute(ctx, req);
    }

    /**
     * Invoked by {@link #execute(ClientRequestContext, Request)}
     * after the deadline for response timeout is set.
     */
    protected abstract O doExecute(ClientRequestContext ctx, I req) throws Exception;

    protected RetryStrategy<I, O> retryStrategy() {
        return retryStrategy;
    }

    /**
     * Resets the {@link ClientRequestContext#responseTimeoutMillis()}.
     *
     * @return {@code true} if the response timeout is set, {@code false} if it can't be set due to the timeout
     */
    protected final boolean setResponseTimeout(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        final long responseTimeoutMillis = ctx.attr(STATE).get().responseTimeoutMillis();
        if (responseTimeoutMillis < 0) {
            return false;
        }
        ctx.setResponseTimeoutMillis(responseTimeoutMillis);
        return true;
    }

    /**
     * Returns the next delay which retry will be made after. The delay will be:
     *
     * <p>{@code Math.min(responseTimeoutMillis, Backoff.nextDelayMillis(int))}
     *
     * @return the number of milliseconds to wait for before attempting a retry
     * @throws RetryGiveUpException if current attempt number is greater than {@code defaultMaxAttempts}
     * @throws ResponseTimeoutException if the remaining response timeout is equal to or less than 0
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
     * @return the number of milliseconds to wait for before attempting a retry
     * @throws RetryGiveUpException if current attempt number is greater than {@code defaultMaxAttempts}
     * @throws ResponseTimeoutException if the remaining response timeout is equal to or less than 0
     */
    protected final long getNextDelay(ClientRequestContext ctx, Backoff backoff, long millisAfterFromServer) {
        requireNonNull(ctx, "ctx");
        requireNonNull(backoff, "backoff");
        final State state = ctx.attr(STATE).get();
        final int currentAttemptNo = state.currentAttemptNoWith(backoff);

        if (currentAttemptNo < 0) {
            // Exceeded the default number of max attempt.
            throw RetryGiveUpException.get();
        }

        long nextDelay = backoff.nextDelayMillis(currentAttemptNo);
        if (nextDelay < 0) {
            // Exceeded the number of max attempt in the backoff.
            throw RetryGiveUpException.get();
        }

        nextDelay = Math.max(nextDelay, millisAfterFromServer);

        if (state.timeoutForWholeRetryEnabled() && nextDelay > state.actualResponseTimeoutMillis()) {
            // Do not wait until the timeout occurs, but throw the Exception as soon as possible.
            throw ResponseTimeoutException.get();
        }

        return nextDelay;
    }

    private static class State {

        private final int defaultMaxAttempts;
        private final long responseTimeoutMillisForEachAttempt;
        private final long responseTimeoutMillis;
        private final long deadlineNanos;

        private Backoff lastBackoff;
        private int currentAttemptNoWithLastBackoff;
        private int totalAttemptNo;

        State(int defaultMaxAttempts, long responseTimeoutMillisForEachAttempt, long responseTimeoutMillis) {
            this.defaultMaxAttempts = defaultMaxAttempts;
            this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
            this.responseTimeoutMillis = responseTimeoutMillis;
            if (responseTimeoutMillis > 0) {
                deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
            } else {
                // Response timeout is disabled.
                deadlineNanos = 0;
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
            return responseTimeoutMillis != 0;
        }

        long actualResponseTimeoutMillis() {
            return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        }

        int currentAttemptNoWith(Backoff backoff) {
            if (totalAttemptNo++ >= defaultMaxAttempts) {
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
