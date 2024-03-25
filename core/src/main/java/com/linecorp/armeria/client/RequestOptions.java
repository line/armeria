/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.client.DefaultRequestOptions.EMPTY;
import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * A {@link RequestOptions} for an {@link HttpRequest}.
 */
@UnstableApi
public interface RequestOptions {

    /**
     * Returns an empty {@link RequestOptions}.
     */
    static RequestOptions of() {
        return EMPTY;
    }

    /**
     * Returns a newly created {@link RequestOptionsBuilder}.
     */
    static RequestOptionsBuilder builder() {
        return new RequestOptionsBuilder(null);
    }

    /**
     * Returns a newly created {@link RequestOptionsBuilder} with the specified {@link RequestOptions}.
     */
    static RequestOptionsBuilder builder(RequestOptions requestOptions) {
        requireNonNull(requestOptions, "requestOptions");
        return new RequestOptionsBuilder(requestOptions);
    }

    /**
     * Returns a new builder created from the properties of this {@link RequestOptions}.
     */
    default RequestOptionsBuilder toBuilder() {
        return new RequestOptionsBuilder(this);
    }

    /**
     * Returns the amount of time allowed until receiving the {@link Response} completely
     * since the transfer of the {@link Response} started or the {@link Request} was fully sent.
     * {@code 0} disables the limit.
     * {@code -1} means the response timeout of a client will be used instead.
     */
    long responseTimeoutMillis();

    /**
     * Returns the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds.
     * {@code 0} disables the limit.
     * {@code -1} means the write timeout of a client will be used instead.
     */
    long writeTimeoutMillis();

    /**
     * Returns the maximum length of the received {@link Response}.
     * {@code 0} disables the limit.
     * {@code -1} means the maximum response length of a client will be used instead.
     */
    long maxResponseLength();

    /**
     * Returns the amount of time to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * {@code null} means the request auto abort delay of a client will be used instead.
     */
    @Nullable
    Long requestAutoAbortDelayMillis();

    /**
     * Returns the {@link Map} of all attributes this {@link RequestOptions} contains.
     */
    Map<AttributeKey<?>, Object> attrs();

    /**
     * Returns the {@link ExchangeType} that determines whether to stream an {@link HttpRequest} or
     * {@link HttpResponse}. {@link ExchangeType#BIDI_STREAMING} is assumed if this method
     * returns {@code null}.
     *
     * <p>Note that an {@link HttpRequest} will be aggregated before being written if
     * {@link ExchangeType#UNARY} or {@link ExchangeType#RESPONSE_STREAMING} is set.
     */
    @Nullable
    ExchangeType exchangeType();
}
