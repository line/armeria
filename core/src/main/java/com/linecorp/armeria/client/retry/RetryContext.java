/*
 * Copyright 2025 LINE Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.client.retry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.TimeoutMode;

class RetryContext<I extends Request, O extends Response> {
    private final ClientRequestContext ctx;
    private final I req;
    private final O res;
    private final CompletableFuture<O> resFuture;
    private final RetryCounter counter;
    private final RetryConfig<O> config;
    private final long deadlineNanos;
    private final boolean isTimeoutEnabled;

    RetryContext(
            ClientRequestContext ctx, I req, O res,
            CompletableFuture<O> resFuture,
            RetryConfig<O> config, long responseTimeoutMillis
    ) {
        this.ctx = ctx;
        this.req = req;
        this.res = res;
        this.resFuture = resFuture;
        this.config = config;
        counter = new RetryCounter(config.maxTotalAttempts());

        if (responseTimeoutMillis <= 0 || responseTimeoutMillis == Long.MAX_VALUE) {
            deadlineNanos = 0;
            isTimeoutEnabled = false;
        } else {
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
            isTimeoutEnabled = true;
        }
    }

    long responseTimeoutMillis() {
        if (!isTimeoutEnabled) {
            return config.responseTimeoutMillisForEachAttempt();
        }

        final long actualResponseTimeoutMillis = actualResponseTimeoutMillis();

        // Consider 0 or less than 0 of actualResponseTimeoutMillis as timed out.
        if (actualResponseTimeoutMillis <= 0) {
            return -1;
        }

        if (config.responseTimeoutMillisForEachAttempt() > 0) {
            return Math.min(config.responseTimeoutMillisForEachAttempt(), actualResponseTimeoutMillis);
        }

        return actualResponseTimeoutMillis;
    }

    public boolean timeoutForWholeRetryEnabled() {
        return isTimeoutEnabled;
    }

    public long actualResponseTimeoutMillis() {
        return TimeUnit.NANOSECONDS.toMillis(
                deadlineNanos - System.nanoTime());
    }

    /**
     * Resets the {@link ClientRequestContext#responseTimeoutMillis()}.
     *
     * @return {@code true} if the response timeout is set, {@code false} if it can't be set due to the timeout
     */
    boolean setResponseTimeout() {
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

    ClientRequestContext ctx() {
        return ctx;
    }

    I req() {
        return req;
    }

    O res() {
        return res;
    }

    RetryConfig<O> config() {
        return config;
    }

    CompletableFuture<O> resFuture() {
        return resFuture;
    }

    RetryCounter counter() {
        return counter;
    }
}
