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

final class DefaultResponseHeadersBuilder
        extends DefaultHttpHeadersBuilder implements ResponseHeadersBuilder {

    private static final String STATUS_HEADER_MISSING = ":status header does not exist.";

    DefaultResponseHeadersBuilder() {}

    DefaultResponseHeadersBuilder(DefaultResponseHeaders headers) {
        super(headers);
    }

    @Override
    public ResponseHeaders build() {
        final HttpHeadersBase delegate = delegate();
        if (delegate != null) {
            checkState(delegate.contains(HttpHeaderNames.STATUS), STATUS_HEADER_MISSING);
            return new DefaultResponseHeaders(promoteDelegate());
        }

        final HttpHeadersBase parent = parent();
        if (parent != null) {
            return (ResponseHeaders) parent;
        }

        // No headers were set.
        throw new IllegalStateException(STATUS_HEADER_MISSING);
    }

    // Response-specific methods.

    @Override
    public HttpStatus status() {
        final HttpHeadersBase getters = getters();
        checkState(getters != null, ":status header does not exist.");
        return getters.status();
    }

    @Override
    public ResponseHeadersBuilder status(int statusCode) {
        setters().status(statusCode);
        return this;
    }

    @Override
    public ResponseHeadersBuilder status(HttpStatus status) {
        setters().status(status);
        return this;
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public ResponseHeadersBuilder sizeHint(int sizeHint) {
        return (ResponseHeadersBuilder) super.sizeHint(sizeHint);
    }

    @Override
    public ResponseHeadersBuilder endOfStream(boolean endOfStream) {
        return (ResponseHeadersBuilder) super.endOfStream(endOfStream);
    }

    @Override
    public ResponseHeadersBuilder contentType(MediaType contentType) {
        return (ResponseHeadersBuilder) super.contentType(contentType);
    }

    @Override
    public ResponseHeadersBuilder add(CharSequence name, String value) {
        return (ResponseHeadersBuilder) super.add(name, value);
    }

    @Override
    public ResponseHeadersBuilder add(CharSequence name, Iterable<String> values) {
        return (ResponseHeadersBuilder) super.add(name, values);
    }

    @Override
    public ResponseHeadersBuilder add(CharSequence name, String... values) {
        return (ResponseHeadersBuilder) super.add(name, values);
    }

    @Override
    public ResponseHeadersBuilder add(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        return (ResponseHeadersBuilder) super.add(headers);
    }

    @Override
    public ResponseHeadersBuilder addObject(CharSequence name, Object value) {
        return (ResponseHeadersBuilder) super.addObject(name, value);
    }

    @Override
    public ResponseHeadersBuilder addObject(CharSequence name, Iterable<?> values) {
        return (ResponseHeadersBuilder) super.addObject(name, values);
    }

    @Override
    public ResponseHeadersBuilder addObject(CharSequence name, Object... values) {
        return (ResponseHeadersBuilder) super.addObject(name, values);
    }

    @Override
    public ResponseHeadersBuilder addObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (ResponseHeadersBuilder) super.addObject(headers);
    }

    @Override
    public ResponseHeadersBuilder addInt(CharSequence name, int value) {
        return (ResponseHeadersBuilder) super.addInt(name, value);
    }

    @Override
    public ResponseHeadersBuilder addLong(CharSequence name, long value) {
        return (ResponseHeadersBuilder) super.addLong(name, value);
    }

    @Override
    public ResponseHeadersBuilder addFloat(CharSequence name, float value) {
        return (ResponseHeadersBuilder) super.addFloat(name, value);
    }

    @Override
    public ResponseHeadersBuilder addDouble(CharSequence name, double value) {
        return (ResponseHeadersBuilder) super.addDouble(name, value);
    }

    @Override
    public ResponseHeadersBuilder addTimeMillis(CharSequence name, long value) {
        return (ResponseHeadersBuilder) super.addTimeMillis(name, value);
    }

    @Override
    public ResponseHeadersBuilder set(CharSequence name, String value) {
        return (ResponseHeadersBuilder) super.set(name, value);
    }

    @Override
    public ResponseHeadersBuilder set(CharSequence name, Iterable<String> values) {
        return (ResponseHeadersBuilder) super.set(name, values);
    }

    @Override
    public ResponseHeadersBuilder set(CharSequence name, String... values) {
        return (ResponseHeadersBuilder) super.set(name, values);
    }

    @Override
    public ResponseHeadersBuilder set(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        return (ResponseHeadersBuilder) super.set(headers);
    }

    @Override
    public ResponseHeadersBuilder setIfAbsent(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        return (ResponseHeadersBuilder) super.setIfAbsent(headers);
    }

    @Override
    public ResponseHeadersBuilder setObject(CharSequence name, Object value) {
        return (ResponseHeadersBuilder) super.setObject(name, value);
    }

    @Override
    public ResponseHeadersBuilder setObject(CharSequence name, Iterable<?> values) {
        return (ResponseHeadersBuilder) super.setObject(name, values);
    }

    @Override
    public ResponseHeadersBuilder setObject(CharSequence name, Object... values) {
        return (ResponseHeadersBuilder) super.setObject(name, values);
    }

    @Override
    public ResponseHeadersBuilder setObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (ResponseHeadersBuilder) super.setObject(headers);
    }

    @Override
    public ResponseHeadersBuilder setInt(CharSequence name, int value) {
        return (ResponseHeadersBuilder) super.setInt(name, value);
    }

    @Override
    public ResponseHeadersBuilder setLong(CharSequence name, long value) {
        return (ResponseHeadersBuilder) super.setLong(name, value);
    }

    @Override
    public ResponseHeadersBuilder setFloat(CharSequence name, float value) {
        return (ResponseHeadersBuilder) super.setFloat(name, value);
    }

    @Override
    public ResponseHeadersBuilder setDouble(CharSequence name, double value) {
        return (ResponseHeadersBuilder) super.setDouble(name, value);
    }

    @Override
    public ResponseHeadersBuilder setTimeMillis(CharSequence name, long value) {
        return (ResponseHeadersBuilder) super.setTimeMillis(name, value);
    }

    @Override
    public ResponseHeadersBuilder removeAndThen(CharSequence name) {
        return (ResponseHeadersBuilder) super.removeAndThen(name);
    }

    @Override
    public ResponseHeadersBuilder clear() {
        return (ResponseHeadersBuilder) super.clear();
    }
}
