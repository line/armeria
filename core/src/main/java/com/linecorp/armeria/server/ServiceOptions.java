/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Options for configuring an {@link HttpService}.
 * You can override the default options by implementing {@link HttpService#options()}.
 */
@UnstableApi
public final class ServiceOptions {
    private static final ServiceOptions DEFAULT_OPTIONS = builder().build();

    /**
     * Returns the default {@link ServiceOptions}.
     */
    public static ServiceOptions of() {
        return DEFAULT_OPTIONS;
    }

    /**
     * Returns a new {@link ServiceOptionsBuilder}.
     */
    public static ServiceOptionsBuilder builder() {
        return new ServiceOptionsBuilder();
    }

    private final long requestTimeoutMillis;
    private final long maxRequestLength;
    private final long requestAutoAbortDelayMillis;
    private final long closeHttp2StreamDelayMillis;

    ServiceOptions(long requestTimeoutMillis, long maxRequestLength, long requestAutoAbortDelayMillis,
                   long closeHttp2StreamDelayMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.maxRequestLength = maxRequestLength;
        this.requestAutoAbortDelayMillis = requestAutoAbortDelayMillis;
        this.closeHttp2StreamDelayMillis = closeHttp2StreamDelayMillis;
    }

    /**
     * Returns the server-side timeout of a request in milliseconds. {@code -1} if not set.
     */
    public long requestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    /**
     * Returns the server-side maximum length of a request. {@code -1} if not set.
     */
    public long maxRequestLength() {
        return maxRequestLength;
    }

    /**
     * Returns the amount of time to wait before aborting an {@link HttpRequest} when its corresponding
     * {@link HttpResponse} is complete. {@code -1} if not set.
     */
    public long requestAutoAbortDelayMillis() {
        return requestAutoAbortDelayMillis;
    }

    /**
     * Sets the amount of time to wait after an {@link HttpRequest} and {@link HttpResponse}
     * is complete before closing the underlying HTTP2 stream. This value will default to {@code 0},
     * which closes the stream immediately. If negative, the delay is disabled.
     * This may be useful for protocols which have a separate lifecycle from the underlying
     * HTTP2 stream such as WebSockets.
     */
    public long closeHttp2StreamDelayMillis() {
        return closeHttp2StreamDelayMillis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final ServiceOptions that = (ServiceOptions) o;

        return requestTimeoutMillis == that.requestTimeoutMillis &&
               maxRequestLength == that.maxRequestLength &&
               requestAutoAbortDelayMillis == that.requestAutoAbortDelayMillis &&
               closeHttp2StreamDelayMillis == that.closeHttp2StreamDelayMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestTimeoutMillis, maxRequestLength, requestAutoAbortDelayMillis,
                            closeHttp2StreamDelayMillis);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("requestTimeoutMillis", requestTimeoutMillis)
                          .add("maxRequestLength", maxRequestLength)
                          .add("requestAutoAbortDelayMillis", requestAutoAbortDelayMillis)
                          .add("closeHttp2StreamDelayMillis", closeHttp2StreamDelayMillis)
                          .toString();
    }
}

