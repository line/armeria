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

import static com.google.common.base.Preconditions.checkState;

import java.util.Map.Entry;

import javax.annotation.Nullable;

/**
 * Builds a {@link RequestHeaders}.
 *
 * @see ResponseHeadersBuilder
 */
public final class RequestHeadersBuilder extends HttpHeadersBuilder implements RequestHeaderGetters {

    RequestHeadersBuilder() {}

    RequestHeadersBuilder(DefaultRequestHeaders headers) {
        super(headers);
    }

    /**
     * Returns a newly created {@link RequestHeaders} with the entries in this builder.
     * Note that any further modification of this builder is prohibited after this method is invoked.
     *
     * @throws IllegalStateException if this builder does not have {@code ":method"} and
     *                               {@code ":path"} headers set.
     */
    @Override
    public RequestHeaders build() {
        final HttpHeadersBase delegate = delegate();
        if (delegate != null) {
            checkState(delegate.contains(HttpHeaderNames.METHOD), ":method header does not exist.");
            checkState(delegate.contains(HttpHeaderNames.PATH), ":path header does not exist.");
            return new DefaultRequestHeaders(promoteDelegate());
        }

        final HttpHeadersBase parent = parent();
        if (parent != null) {
            return (RequestHeaders) parent;
        }

        // No headers were set.
        throw new IllegalStateException("must set ':method' and ':path' headers");
    }

    @Override
    public RequestHeadersBuilder sizeHint(int sizeHint) {
        return (RequestHeadersBuilder) super.sizeHint(sizeHint);
    }

    // Request-specific methods.

    @Override
    public HttpMethod method() {
        final HttpHeadersBase getters = getters();
        checkState(getters != null, ":method header does not exist.");
        return getters.method();
    }

    /**
     * Sets the {@code ":method"} header.
     */
    public RequestHeadersBuilder method(HttpMethod method) {
        setters().method(method);
        return this;
    }

    @Override
    public String path() {
        final HttpHeadersBase getters = getters();
        checkState(getters != null, ":path header does not exist.");
        return getters.path();
    }

    /**
     * Sets the {@code ":path"} headers.
     */
    public RequestHeadersBuilder path(String path) {
        setters().path(path);
        return this;
    }

    @Nullable
    @Override
    public String scheme() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.scheme() : null;
    }

    /**
     * Sets the {@code ":scheme"} headers.
     */
    public RequestHeadersBuilder scheme(String scheme) {
        setters().scheme(scheme);
        return this;
    }

    @Nullable
    @Override
    public String authority() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.authority() : null;
    }

    /**
     * Sets the {@code ":authority"} headers.
     */
    public RequestHeadersBuilder authority(String authority) {
        setters().authority(authority);
        return this;
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public RequestHeadersBuilder endOfStream(boolean endOfStream) {
        return (RequestHeadersBuilder) super.endOfStream(endOfStream);
    }

    @Override
    public RequestHeadersBuilder contentType(MediaType contentType) {
        return (RequestHeadersBuilder) super.contentType(contentType);
    }

    @Override
    public RequestHeadersBuilder add(CharSequence name, String value) {
        return (RequestHeadersBuilder) super.add(name, value);
    }

    @Override
    public RequestHeadersBuilder add(CharSequence name, Iterable<String> values) {
        return (RequestHeadersBuilder) super.add(name, values);
    }

    @Override
    public RequestHeadersBuilder add(CharSequence name, String... values) {
        return (RequestHeadersBuilder) super.add(name, values);
    }

    @Override
    public RequestHeadersBuilder add(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        return (RequestHeadersBuilder) super.add(headers);
    }

    @Override
    public RequestHeadersBuilder addObject(CharSequence name, Object value) {
        return (RequestHeadersBuilder) super.addObject(name, value);
    }

    @Override
    public RequestHeadersBuilder addObject(CharSequence name, Iterable<?> values) {
        return (RequestHeadersBuilder) super.addObject(name, values);
    }

    @Override
    public RequestHeadersBuilder addObject(CharSequence name, Object... values) {
        return (RequestHeadersBuilder) super.addObject(name, values);
    }

    @Override
    public RequestHeadersBuilder addObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (RequestHeadersBuilder) super.addObject(headers);
    }

    @Override
    public RequestHeadersBuilder addInt(CharSequence name, int value) {
        return (RequestHeadersBuilder) super.addInt(name, value);
    }

    @Override
    public RequestHeadersBuilder addLong(CharSequence name, long value) {
        return (RequestHeadersBuilder) super.addLong(name, value);
    }

    @Override
    public RequestHeadersBuilder addFloat(CharSequence name, float value) {
        return (RequestHeadersBuilder) super.addFloat(name, value);
    }

    @Override
    public RequestHeadersBuilder addDouble(CharSequence name, double value) {
        return (RequestHeadersBuilder) super.addDouble(name, value);
    }

    @Override
    public RequestHeadersBuilder addTimeMillis(CharSequence name, long value) {
        return (RequestHeadersBuilder) super.addTimeMillis(name, value);
    }

    @Override
    public RequestHeadersBuilder set(CharSequence name, String value) {
        return (RequestHeadersBuilder) super.set(name, value);
    }

    @Override
    public RequestHeadersBuilder set(CharSequence name, Iterable<String> values) {
        return (RequestHeadersBuilder) super.set(name, values);
    }

    @Override
    public RequestHeadersBuilder set(CharSequence name, String... values) {
        return (RequestHeadersBuilder) super.set(name, values);
    }

    @Override
    public RequestHeadersBuilder set(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        return (RequestHeadersBuilder) super.set(headers);
    }

    @Override
    public RequestHeadersBuilder setIfAbsent(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        return (RequestHeadersBuilder) super.setIfAbsent(headers);
    }

    @Override
    public RequestHeadersBuilder setObject(CharSequence name, Object value) {
        return (RequestHeadersBuilder) super.setObject(name, value);
    }

    @Override
    public RequestHeadersBuilder setObject(CharSequence name, Iterable<?> values) {
        return (RequestHeadersBuilder) super.setObject(name, values);
    }

    @Override
    public RequestHeadersBuilder setObject(CharSequence name, Object... values) {
        return (RequestHeadersBuilder) super.setObject(name, values);
    }

    @Override
    public RequestHeadersBuilder setObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (RequestHeadersBuilder) super.setObject(headers);
    }

    @Override
    public RequestHeadersBuilder setInt(CharSequence name, int value) {
        return (RequestHeadersBuilder) super.setInt(name, value);
    }

    @Override
    public RequestHeadersBuilder setLong(CharSequence name, long value) {
        return (RequestHeadersBuilder) super.setLong(name, value);
    }

    @Override
    public RequestHeadersBuilder setFloat(CharSequence name, float value) {
        return (RequestHeadersBuilder) super.setFloat(name, value);
    }

    @Override
    public RequestHeadersBuilder setDouble(CharSequence name, double value) {
        return (RequestHeadersBuilder) super.setDouble(name, value);
    }

    @Override
    public RequestHeadersBuilder setTimeMillis(CharSequence name, long value) {
        return (RequestHeadersBuilder) super.setTimeMillis(name, value);
    }

    @Override
    public RequestHeadersBuilder removeAndThen(CharSequence name) {
        return (RequestHeadersBuilder) super.removeAndThen(name);
    }

    @Override
    public RequestHeadersBuilder clear() {
        return (RequestHeadersBuilder) super.clear();
    }
}
