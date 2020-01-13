/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.throttling;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * Determines whether a request should be throttled.
 */
public abstract class RetryThrottlingStrategy<T extends Request> extends ThrottlingStrategy<T> {

    private static final HttpStatus DEFAULT_STATUS = HttpStatus.TOO_MANY_REQUESTS;

    private final HttpStatus status;

    /**
     * Creates a new {@link ThrottlingStrategy} with a default name
     * and default {@link HttpStatus#TOO_MANY_REQUESTS} status.
     */
    protected RetryThrottlingStrategy() {
        super(null);
        status = DEFAULT_STATUS;
    }

    /**
     * Creates a new {@link ThrottlingStrategy} with specified name
     * and default {@link HttpStatus#TOO_MANY_REQUESTS} status.
     */
    protected RetryThrottlingStrategy(@Nullable String name) {
        super(name);
        status = DEFAULT_STATUS;
    }

    /**
     * Creates a new {@link ThrottlingStrategy} with specified name and status.
     * Acceptable statuses: {@link HttpStatus#TOO_MANY_REQUESTS}, {@link HttpStatus#SERVICE_UNAVAILABLE}
     * and {@link HttpStatus#MOVED_PERMANENTLY}.
     */
    protected RetryThrottlingStrategy(@Nullable String name, @Nonnull HttpStatus status) {
        super(name);
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * Supplied a value for {@link HttpHeaderNames#RETRY_AFTER} header.
     */
    @Nullable
    protected abstract String retryAfterSeconds();

    /**
     * Constructs {@link ResponseHeaders} for the given status
     * and optionally includes {@link HttpHeaderNames#RETRY_AFTER} header, if its value provided.
     */
    protected ResponseHeaders getResponseHeaders() {
        final String retryAfterSeconds = retryAfterSeconds();
        if (retryAfterSeconds != null) {
            return ResponseHeaders.of(status, HttpHeaderNames.RETRY_AFTER, retryAfterSeconds);
        } else {
            return ResponseHeaders.of(status);
        }
    }
}
