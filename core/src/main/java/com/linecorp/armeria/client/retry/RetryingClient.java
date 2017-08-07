/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.retry;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;

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

    private static final AttributeKey<Long> RESPONSE_TIMEOUT_DEADLINE_NANOS =
            AttributeKey.valueOf("RESPONSE_TIMEOUT_DEADLINE_NANOS");

    private final Supplier<? extends Backoff> backoffSupplier;
    private final RetryStrategy<I, O> retryStrategy;
    private final int defaultMaxAttempts;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    protected RetryingClient(Client<I, O> delegate, RetryStrategy<I, O> retryStrategy,
                             Supplier<? extends Backoff> backoffSupplier, int defaultMaxAttempts) {
        super(delegate);
        this.retryStrategy = requireNonNull(retryStrategy, "retryStrategy");
        this.backoffSupplier = requireNonNull(backoffSupplier, "backoffSupplier");
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        setDeadlineOfThisRequest(ctx);
        return doExecute(ctx, req, newBackoff());
    }

    private static void setDeadlineOfThisRequest(ClientRequestContext ctx) {
        final Attribute<Long> deadlineNanosAttr = ctx.attr(RESPONSE_TIMEOUT_DEADLINE_NANOS);
        if (ctx.responseTimeoutMillis() <= 0) {
            deadlineNanosAttr.set(-1L);
        } else {
            deadlineNanosAttr.set(
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ctx.responseTimeoutMillis()));
        }
    }

    @VisibleForTesting
    Backoff newBackoff() {
        Backoff backoff = backoffSupplier.get();
        if (!backoff.as(AttemptLimitingBackoff.class).isPresent()) {
            backoff = backoff.withMaxAttempts(defaultMaxAttempts);
        }
        return backoff;
    }

    /**
     * Invoked by {@link #execute(ClientRequestContext, Request)}
     * after the deadline for response timeout is set.
     */
    protected abstract O doExecute(ClientRequestContext ctx, I req, Backoff backoff) throws Exception;

    protected RetryStrategy<I, O> retryStrategy() {
        return retryStrategy;
    }

    /**
     * Resets the {@link ClientRequestContext#responseTimeoutMillis()}.
     * @throws ResponseTimeoutException if the remaining response timeout is equal to or less than 0
     */
    protected final void resetResponseTimeout(ClientRequestContext ctx) {
        final long responseTimeoutMillis = responseTimeoutMillis(ctx);
        if (responseTimeoutMillis < 0) { // response timeout is disabled.
            return;
        }

        ctx.setResponseTimeoutMillis(responseTimeoutMillis);
    }

    /**
     * Returns the next delay which retry will be made after. The delay is the smaller value of
     * {@code nextDelay} and {@code responseTimeoutMillis}. If response timeout is disabled,
     * just returns {@code nextDelay}.
     * @throws ResponseTimeoutException if the remaining response timeout is equal to or less than 0
     */
    protected final long getNextDelay(long nextDelay, ClientRequestContext ctx) {
        long responseTimeoutMillis = responseTimeoutMillis(ctx);
        return responseTimeoutMillis < 0 ? nextDelay : Math.min(nextDelay, responseTimeoutMillis);
    }

    private static long responseTimeoutMillis(ClientRequestContext ctx) {
        final Long deadlineNanos = ctx.attr(RESPONSE_TIMEOUT_DEADLINE_NANOS).get();
        if (deadlineNanos < 0) { // response timeout is disabled.
            return -1;
        }

        final long responseTimeoutMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (responseTimeoutMillis > 0) { // 0 is not from the first, but subtracted to that, so it's timeout.
            return responseTimeoutMillis;
        }

        throw ResponseTimeoutException.get(); // timeout!!
    }
}
