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

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Creates a new {@link ServiceOptions} with the specified parameters.
 */
@UnstableApi
public final class ServiceOptionsBuilder {
    private long requestTimeoutMillis = -1;
    private long maxRequestLength = -1;
    private long requestAutoAbortDelayMillis = -1;

    ServiceOptionsBuilder() {}

    /**
     * Returns the server-side timeout of a request in milliseconds.
     */
    public ServiceOptionsBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        checkArgument(requestTimeoutMillis >= 0, "requestTimeoutMillis: %s (expected: >= 0)",
                      requestTimeoutMillis);
        this.requestTimeoutMillis = requestTimeoutMillis;
        return this;
    }

    /**
     * Returns the server-side maximum length of a request.
     */
    public ServiceOptionsBuilder maxRequestLength(long maxRequestLength) {
        checkArgument(maxRequestLength >= 0, "maxRequestLength: %s (expected: >= 0)", maxRequestLength);
        this.maxRequestLength = maxRequestLength;
        return this;
    }

    /**
     * Sets the amount of time to wait before aborting an {@link HttpRequest} when its corresponding
     * {@link HttpResponse} is complete.
     */
    public ServiceOptionsBuilder requestAutoAbortDelayMillis(long requestAutoAbortDelayMillis) {
        checkArgument(requestAutoAbortDelayMillis >= 0, "requestAutoAbortDelayMillis: %s (expected: >= 0)",
                      requestAutoAbortDelayMillis);
        this.requestAutoAbortDelayMillis = requestAutoAbortDelayMillis;
        return this;
    }

    /**
     * Returns a newly created {@link ServiceOptions} based on the properties of this builder.
     */
    public ServiceOptions build() {
        return new ServiceOptions(requestTimeoutMillis, maxRequestLength, requestAutoAbortDelayMillis);
    }
}
