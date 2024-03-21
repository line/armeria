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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.DefaultRequestOptions.EMPTY;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

/**
 * A builder for creating a new {@link RequestOptions}.
 */
public final class RequestOptionsBuilder implements RequestOptionsSetters {

    private long responseTimeoutMillis = -1;
    private long writeTimeoutMillis = -1;
    private long maxResponseLength = -1;
    @Nullable
    private Long requestAutoAbortDelayMillis;

    @Nullable
    private Map<AttributeKey<?>, Object> attributes;
    @Nullable
    private ExchangeType exchangeType;

    RequestOptionsBuilder(@Nullable RequestOptions options) {
        if (options != null) {
            responseTimeoutMillis = options.responseTimeoutMillis();
            writeTimeoutMillis = options.writeTimeoutMillis();
            maxResponseLength = options.maxResponseLength();
            requestAutoAbortDelayMillis = options.requestAutoAbortDelayMillis();
            final Map<AttributeKey<?>, Object> attrs = options.attrs();
            if (!attrs.isEmpty()) {
                attributes = new HashMap<>(attrs);
            }
            exchangeType = options.exchangeType();
        }
    }

    @Override
    public RequestOptionsBuilder responseTimeout(Duration responseTimeout) {
        responseTimeoutMillis(requireNonNull(responseTimeout, "responseTimeout").toMillis());
        return this;
    }

    @Override
    public RequestOptionsBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        checkArgument(responseTimeoutMillis >= 0, "responseTimeoutMillis: %s (expected: >= 0)",
                      responseTimeoutMillis);
        this.responseTimeoutMillis = responseTimeoutMillis;
        return this;
    }

    @Override
    public RequestOptionsBuilder writeTimeout(Duration writeTimeout) {
        writeTimeoutMillis(requireNonNull(writeTimeout, "writeTimeout").toMillis());
        return this;
    }

    @Override
    public RequestOptionsBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        checkArgument(writeTimeoutMillis >= 0, "writeTimeoutMillis: %s (expected: >= 0)",
                      writeTimeoutMillis);
        this.writeTimeoutMillis = writeTimeoutMillis;
        return this;
    }

    @Override
    public RequestOptionsBuilder maxResponseLength(long maxResponseLength) {
        checkArgument(maxResponseLength >= 0, "maxResponseLength: %s (expected: >= 0)", maxResponseLength);
        this.maxResponseLength = maxResponseLength;
        return this;
    }

    @Override
    public RequestOptionsBuilder requestAutoAbortDelay(Duration delay) {
        return requestAutoAbortDelayMillis(requireNonNull(delay, "delay").toMillis());
    }

    @Override
    public RequestOptionsBuilder requestAutoAbortDelayMillis(long delayMillis) {
        requestAutoAbortDelayMillis = delayMillis;
        return this;
    }

    @Override
    public <V> RequestOptionsBuilder attr(AttributeKey<V> key, @Nullable V value) {
        requireNonNull(key, "key");

        if (attributes == null) {
            attributes = new HashMap<>();
        }

        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
        return this;
    }

    @Nullable
    ExchangeType exchangeType() {
        return exchangeType;
    }

    @Override
    public RequestOptionsBuilder exchangeType(ExchangeType exchangeType) {
        requireNonNull(exchangeType, "exchangeType");
        this.exchangeType = exchangeType;
        return this;
    }

    /**
     * Returns a newly created {@link RequestOptions} with the properties specified so far.
     */
    public RequestOptions build() {
        if (responseTimeoutMillis < 0 && writeTimeoutMillis < 0 &&
            maxResponseLength < 0 && requestAutoAbortDelayMillis == null && attributes == null &&
            exchangeType == null) {
            return EMPTY;
        } else {
            final Map<AttributeKey<?>, Object> attributes;
            if (this.attributes == null || this.attributes.isEmpty()) {
                attributes = ImmutableMap.of();
            } else {
                attributes = ImmutableMap.copyOf(this.attributes);
            }
            return new DefaultRequestOptions(responseTimeoutMillis, writeTimeoutMillis,
                                             maxResponseLength, requestAutoAbortDelayMillis,
                                             attributes, exchangeType);
        }
    }
}
