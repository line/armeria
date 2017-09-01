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

import io.netty.util.Attribute;
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

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    protected RetryingClient(Client<I, O> delegate,
                             RetryStrategy<I, O> retryStrategy, int defaultMaxAttempts) {
        super(delegate);
        this.retryStrategy = requireNonNull(retryStrategy, "retryStrategy");
        checkArgument(defaultMaxAttempts > 0, "defaultMaxAttempts: %s (expected: > 0)", defaultMaxAttempts);
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        setState(ctx);
        return doExecute(ctx, req);
    }

    private void setState(ClientRequestContext ctx) {
        final Attribute<State> attr = ctx.attr(STATE);
        final State state = attr.get();
        if (state != null) {
            state.init(ctx.responseTimeoutMillis());
        } else {
            attr.set(new State(defaultMaxAttempts, ctx.responseTimeoutMillis()));
        }
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
     * @throws ResponseTimeoutException if the remaining response timeout is equal to or less than 0
     */
    protected final void resetResponseTimeout(ClientRequestContext ctx) {
        final long responseTimeoutMillis = responseTimeoutMillis(ctx.attr(STATE).get());
        if (responseTimeoutMillis < 0) { // response timeout is disabled.
            return;
        }

        ctx.setResponseTimeoutMillis(responseTimeoutMillis);
    }

    /**
     * Returns the next delay which retry will be made after. The delay will be:
     *
     * <p>{@code Math.min(responseTimeoutMillis, Backoff.nextDelayMillis(int))}
     *
     * @return the number of milliseconds to wait for before attempting a retry,
     *         or a negative value if current attempt number is greater than {@code defaultMaxAttempts}
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
     * @return the number of milliseconds to wait for before attempting a retry,
     *         or a negative value if current attempt number is greater than {@code defaultMaxAttempts}
     * @throws ResponseTimeoutException if the remaining response timeout is equal to or less than 0
     */
    protected final long getNextDelay(ClientRequestContext ctx, Backoff backoff, long millisAfterFromServer) {
        requireNonNull(ctx, "ctx");
        requireNonNull(backoff, "backoff");
        final State state = ctx.attr(STATE).get();
        final int currentAttemptNo = state.currentAttemptNoWith(backoff);
        if (currentAttemptNo < 0) {
            return -1;
        }

        long nextDelay = backoff.nextDelayMillis(currentAttemptNo);
        if (nextDelay < 0) {
            return -1;
        }

        nextDelay = Math.max(nextDelay, millisAfterFromServer);
        long responseTimeoutMillis = responseTimeoutMillis(state);
        return responseTimeoutMillis < 0 ? nextDelay : Math.min(nextDelay, responseTimeoutMillis);
    }

    private static long responseTimeoutMillis(State state) {
        final long deadlineNanos = state.deadlineNanos;
        if (deadlineNanos < 0) { // response timeout is disabled.
            return -1;
        }

        final long responseTimeoutMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (responseTimeoutMillis > 0) {
            return responseTimeoutMillis;
        }

        // 0 is not from the first, but subtracted to that, so it's timeout.
        throw ResponseTimeoutException.get();
    }

    private static class State {

        private final int defaultMaxAttempts;

        private Backoff lastBackoff;
        private int currentAttemptNoWithLastBackoff;
        private int totalAttemptNo;
        long deadlineNanos;

        State(int defaultMaxAttempts, long responseTimeoutMillis) {
            init(responseTimeoutMillis);
            this.defaultMaxAttempts = defaultMaxAttempts;
        }

        void init(long responseTimeoutMillis) {
            setDeadlineOfThisRequest(responseTimeoutMillis);
            lastBackoff = null;
            totalAttemptNo = 1;
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

        private void setDeadlineOfThisRequest(long responseTimeoutMillis) {
            if (responseTimeoutMillis <= 0) {
                deadlineNanos = -1;
            } else {
                deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
            }
        }
    }
}
