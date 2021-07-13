/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.Locale.LanguageRange;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

/**
 * Builds a {@link RequestHeaders}.
 *
 * @see RequestHeaders#builder()
 * @see RequestHeaders#toBuilder()
 */
public interface RequestHeadersBuilder extends HttpHeadersBuilder, RequestHeaderGetters {
    /**
     * Returns a newly created {@link RequestHeaders} with the entries in this builder.
     *
     * @throws IllegalStateException if this builder does not have {@code ":method"} and
     *                               {@code ":path"} headers set.
     */
    @Override
    RequestHeaders build();

    /**
     * Sets the {@code ":method"} header.
     */
    RequestHeadersBuilder method(HttpMethod method);

    /**
     * Sets the {@code ":path"} header.
     */
    RequestHeadersBuilder path(String path);

    /**
     * Sets the {@code ":scheme"} header.
     */
    RequestHeadersBuilder scheme(String scheme);

    /**
     * Sets the {@code ":scheme"} header from the specified {@link SessionProtocol}.
     */
    default RequestHeadersBuilder scheme(SessionProtocol sessionProtocol) {
        requireNonNull(sessionProtocol, "sessionProtocol");
        switch (sessionProtocol) {
            case HTTPS:
            case H2:
            case H1:
                scheme("https");
                break;
            case HTTP:
            case H2C:
            case H1C:
                scheme("http");
                break;
            default:
                throw new IllegalArgumentException("sessionProtocol: " + sessionProtocol +
                                                   " (expected: HTTPS, H2, H1, HTTP, H2C or H1C)");
        }
        return this;
    }

    /**
     * Sets the {@code ":authority"} header.
     */
    RequestHeadersBuilder authority(String authority);

    /**
     * Sets the {@code ":authority"} header from the specified {@link Endpoint}.
     *
     * @throws IllegalArgumentException if the specified {@link Endpoint} refers to a group
     */
    default RequestHeadersBuilder authority(Endpoint endpoint) {
        requireNonNull(endpoint, "endpoint");
        return authority(endpoint.authority());
    }

    /**
     * Sets the {@code "accept-language"} header.
     * @param acceptedLanguages the accepted languages.
     * @return {@code this}
     */
    RequestHeadersBuilder acceptLanguages(Iterable<LanguageRange> acceptedLanguages);

    /**
     * Sets the {@code "accept-language"} header.
     * @param acceptedLanguages the accepted languages.
     * @return {@code this}
     */
    default RequestHeadersBuilder acceptLanguages(LanguageRange... acceptedLanguages) {
        return acceptLanguages(ImmutableList.copyOf(
                requireNonNull(acceptedLanguages, "acceptedLanguages")));
    }

    /**
     * Sets the <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-4.2">cookie</a> header.
     */
    RequestHeadersBuilder cookie(Cookie cookie);

    /**
     * Sets the <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-4.2">cookie</a> header.
     */
    RequestHeadersBuilder cookies(Iterable<? extends Cookie> cookies);

    /**
     * Sets the <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-4.2">cookie</a> header.
     */
    RequestHeadersBuilder cookies(Cookie... cookies);

    // Override the return type of the chaining methods in the superclass.

    @Override
    RequestHeadersBuilder sizeHint(int sizeHint);

    @Override
    RequestHeadersBuilder endOfStream(boolean endOfStream);

    @Override
    RequestHeadersBuilder contentType(MediaType contentType);

    @Override
    RequestHeadersBuilder contentDisposition(ContentDisposition contentDisposition);

    @Override
    RequestHeadersBuilder add(CharSequence name, String value);

    @Override
    RequestHeadersBuilder add(CharSequence name, Iterable<String> values);

    @Override
    RequestHeadersBuilder add(CharSequence name, String... values);

    @Override
    RequestHeadersBuilder add(Iterable<? extends Entry<? extends CharSequence, String>> entries);

    @Override
    default RequestHeadersBuilder add(Map<? extends CharSequence, String> entries) {
        return (RequestHeadersBuilder) HttpHeadersBuilder.super.add(entries);
    }

    @Override
    RequestHeadersBuilder addObject(CharSequence name, Object value);

    @Override
    RequestHeadersBuilder addObject(CharSequence name, Iterable<?> values);

    @Override
    RequestHeadersBuilder addObject(CharSequence name, Object... values);

    @Override
    RequestHeadersBuilder addObject(Iterable<? extends Entry<? extends CharSequence, ?>> entries);

    @Override
    RequestHeadersBuilder addInt(CharSequence name, int value);

    @Override
    RequestHeadersBuilder addLong(CharSequence name, long value);

    @Override
    RequestHeadersBuilder addFloat(CharSequence name, float value);

    @Override
    RequestHeadersBuilder addDouble(CharSequence name, double value);

    @Override
    RequestHeadersBuilder addTimeMillis(CharSequence name, long value);

    @Override
    RequestHeadersBuilder set(CharSequence name, String value);

    @Override
    RequestHeadersBuilder set(CharSequence name, Iterable<String> values);

    @Override
    RequestHeadersBuilder set(CharSequence name, String... values);

    @Override
    RequestHeadersBuilder set(Iterable<? extends Entry<? extends CharSequence, String>> entries);

    @Override
    default RequestHeadersBuilder set(Map<? extends CharSequence, String> entries) {
        return (RequestHeadersBuilder) HttpHeadersBuilder.super.set(entries);
    }

    @Override
    RequestHeadersBuilder setIfAbsent(Iterable<? extends Entry<? extends CharSequence, String>> entries);

    @Override
    RequestHeadersBuilder setObject(CharSequence name, Object value);

    @Override
    RequestHeadersBuilder setObject(CharSequence name, Iterable<?> values);

    @Override
    RequestHeadersBuilder setObject(CharSequence name, Object... values);

    @Override
    RequestHeadersBuilder setObject(Iterable<? extends Entry<? extends CharSequence, ?>> entries);

    @Override
    RequestHeadersBuilder setInt(CharSequence name, int value);

    @Override
    RequestHeadersBuilder setLong(CharSequence name, long value);

    @Override
    RequestHeadersBuilder setFloat(CharSequence name, float value);

    @Override
    RequestHeadersBuilder setDouble(CharSequence name, double value);

    @Override
    RequestHeadersBuilder setTimeMillis(CharSequence name, long value);

    @Override
    RequestHeadersBuilder removeAndThen(CharSequence name);

    @Override
    RequestHeadersBuilder clear();
}
