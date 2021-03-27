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

import java.util.Map.Entry;

/**
 * Builds a {@link ResponseHeaders}.
 *
 * @see ResponseHeaders#builder()
 * @see ResponseHeaders#toBuilder()
 */
public interface ResponseHeadersBuilder extends HttpHeadersBuilder, ResponseHeaderGetters {
    /**
     * Returns a newly created {@link ResponseHeaders} with the entries in this builder.
     *
     * @throws IllegalStateException if this builder does not have {@code ":status"} header set.
     */
    @Override
    ResponseHeaders build();

    /**
     * Sets the {@code ":status"} header.
     */
    ResponseHeadersBuilder status(int statusCode);

    /**
     * Sets the {@code ":status"} header.
     */
    ResponseHeadersBuilder status(HttpStatus status);

    /**
     * Sets the {@code "set-cookie"} header.\
     */
    ResponseHeadersBuilder cookie(Cookie cookie);

    /**
     * Sets the {@code "set-cookie"} header.\
     */
    ResponseHeadersBuilder cookies(Iterable<? extends Cookie> cookies);

    /**
     * Sets the {@code "set-cookie"} header.\
     */
    ResponseHeadersBuilder cookies(Cookie... cookies);

    // Override the return type of the chaining methods in the superclass.

    @Override
    ResponseHeadersBuilder sizeHint(int sizeHint);

    @Override
    ResponseHeadersBuilder endOfStream(boolean endOfStream);

    @Override
    ResponseHeadersBuilder contentType(MediaType contentType);

    @Override
    ResponseHeadersBuilder contentDisposition(ContentDisposition contentDisposition);

    @Override
    ResponseHeadersBuilder add(CharSequence name, String value);

    @Override
    ResponseHeadersBuilder add(CharSequence name, Iterable<String> values);

    @Override
    ResponseHeadersBuilder add(CharSequence name, String... values);

    @Override
    ResponseHeadersBuilder add(Iterable<? extends Entry<? extends CharSequence, String>> entries);

    @Override
    ResponseHeadersBuilder addObject(CharSequence name, Object value);

    @Override
    ResponseHeadersBuilder addObject(CharSequence name, Iterable<?> values);

    @Override
    ResponseHeadersBuilder addObject(CharSequence name, Object... values);

    @Override
    ResponseHeadersBuilder addObject(Iterable<? extends Entry<? extends CharSequence, ?>> entries);

    @Override
    ResponseHeadersBuilder addInt(CharSequence name, int value);

    @Override
    ResponseHeadersBuilder addLong(CharSequence name, long value);

    @Override
    ResponseHeadersBuilder addFloat(CharSequence name, float value);

    @Override
    ResponseHeadersBuilder addDouble(CharSequence name, double value);

    @Override
    ResponseHeadersBuilder addTimeMillis(CharSequence name, long value);

    @Override
    ResponseHeadersBuilder set(CharSequence name, String value);

    @Override
    ResponseHeadersBuilder set(CharSequence name, Iterable<String> values);

    @Override
    ResponseHeadersBuilder set(CharSequence name, String... values);

    @Override
    ResponseHeadersBuilder set(Iterable<? extends Entry<? extends CharSequence, String>> entries);

    @Override
    ResponseHeadersBuilder setIfAbsent(Iterable<? extends Entry<? extends CharSequence, String>> entries);

    @Override
    ResponseHeadersBuilder setObject(CharSequence name, Object value);

    @Override
    ResponseHeadersBuilder setObject(CharSequence name, Iterable<?> values);

    @Override
    ResponseHeadersBuilder setObject(CharSequence name, Object... values);

    @Override
    ResponseHeadersBuilder setObject(Iterable<? extends Entry<? extends CharSequence, ?>> entries);

    @Override
    ResponseHeadersBuilder setInt(CharSequence name, int value);

    @Override
    ResponseHeadersBuilder setLong(CharSequence name, long value);

    @Override
    ResponseHeadersBuilder setFloat(CharSequence name, float value);

    @Override
    ResponseHeadersBuilder setDouble(CharSequence name, double value);

    @Override
    ResponseHeadersBuilder setTimeMillis(CharSequence name, long value);

    @Override
    ResponseHeadersBuilder removeAndThen(CharSequence name);

    @Override
    ResponseHeadersBuilder clear();
}
