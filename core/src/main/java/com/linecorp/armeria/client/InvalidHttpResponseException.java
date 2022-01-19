/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An {@link InvalidResponseException} raised when a client received an invalid {@link HttpResponse}.
 */
@UnstableApi
public final class InvalidHttpResponseException extends InvalidResponseException {

    private static final long serialVersionUID = 3883287492432644897L;

    private final AggregatedHttpResponse response;

    /**
     * Creates a new instance with the specified {@link AggregatedHttpResponse}.
     *
     * @throws IllegalArgumentException if the {@link AggregatedHttpResponse} has a pooled content.
     */
    public InvalidHttpResponseException(AggregatedHttpResponse response) {
        this(response, null);
    }

    /**
     * Creates a new instance with the specified {@link AggregatedHttpResponse} and {@link Throwable}.
     *
     * @throws IllegalArgumentException if the {@link AggregatedHttpResponse} has a pooled content.
     */
    public InvalidHttpResponseException(AggregatedHttpResponse response, @Nullable Throwable cause) {
        super(requireNonNull(response, "response").toString(), cause);
        ensureNonPooledObject(response);
        this.response = response;
    }

    /**
     * Creates a new instance with the specified {@link AggregatedHttpResponse}, {@code message} and
     * {@link Throwable}.
     *
     * @throws IllegalArgumentException if the {@link AggregatedHttpResponse} has a pooled content.
     */
    public InvalidHttpResponseException(AggregatedHttpResponse response, String message,
                                        @Nullable Throwable cause) {
        super(requireNonNull(message, "message"), cause);
        ensureNonPooledObject(response);
        this.response = response;
    }

    /**
     * Returns the {@link AggregatedHttpResponse} which triggered this exception.
     */
    public AggregatedHttpResponse response() {
        return response;
    }

    private static void ensureNonPooledObject(AggregatedHttpResponse response) {
        checkArgument(!response.content().isPooled(),
                      "Cannot create an %s with the pooled content: %s (expected: a non-pooled content)",
                      InvalidHttpResponseException.class.getSimpleName(), response);
    }
}
