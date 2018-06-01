/*
 * Copyright 2016 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Ascii;

import com.linecorp.armeria.internal.ArmeriaHttpUtil;

import io.netty.handler.codec.DefaultHeaders;
import io.netty.util.AsciiString;

/**
 * Default {@link HttpHeaders} implementation.
 */
public final class DefaultHttpHeaders
        extends DefaultHeaders<AsciiString, String, HttpHeaders> implements HttpHeaders {

    private static final NameValidator<AsciiString> HTTP2_NAME_VALIDATOR =
            name -> checkArgument(name != null && !name.isEmpty(), "empty headers are not allowed: %s", name);

    private final boolean endOfStream;

    @Nullable
    private HttpMethod method;
    @Nullable
    private HttpStatus status;
    @Nullable
    private MediaType contentType;

    /**
     * Creates a new instance.
     */
    public DefaultHttpHeaders() {
        this(true);
    }

    /**
     * Creates a new instance.
     *
     * @param validate whether to validate the header names and values
     */
    public DefaultHttpHeaders(boolean validate) {
        this(validate, 16);
    }

    /**
     * Creates a new instance.
     *
     * @param validate whether to validate the header names and values
     * @param initialCapacity the initial capacity of the internal data structure
     */
    public DefaultHttpHeaders(boolean validate, int initialCapacity) {
        this(validate, initialCapacity, false);
    }

    /**
     * Creates a new instance.
     *
     * @param validate whether to validate the header names and values
     * @param initialCapacity the initial capacity of the internal data structure
     * @param endOfStream whether the stream should be closed after writing these headers
     */
    public DefaultHttpHeaders(boolean validate, int initialCapacity, boolean endOfStream) {
        super(ArmeriaHttpUtil.HTTP2_HEADER_NAME_HASHER,
              StringValueConverter.INSTANCE,
              validate ? HTTP2_NAME_VALIDATOR : NameValidator.NOT_NULL, initialCapacity);
        this.endOfStream = endOfStream;
    }

    @Nullable
    @Override
    public HttpMethod method() {
        final HttpMethod method = this.method;
        if (method != null) {
            return method;
        }

        final String methodStr = get(HttpHeaderNames.METHOD);
        if (methodStr == null) {
            return null;
        }

        return this.method = HttpMethod.isSupported(methodStr) ? HttpMethod.valueOf(methodStr)
                                                               : HttpMethod.UNKNOWN;
    }

    @Override
    public HttpHeaders method(HttpMethod method) {
        requireNonNull(method, "method");
        this.method = method;
        set(HttpHeaderNames.METHOD, method.name());
        return this;
    }

    @Override
    public String scheme() {
        return get(HttpHeaderNames.SCHEME);
    }

    @Override
    public HttpHeaders scheme(String scheme) {
        requireNonNull(scheme, "scheme");
        set(HttpHeaderNames.SCHEME, scheme);
        return this;
    }

    @Override
    public String authority() {
        return get(HttpHeaderNames.AUTHORITY);
    }

    @Override
    public HttpHeaders authority(String authority) {
        requireNonNull(authority, "authority");
        set(HttpHeaderNames.AUTHORITY, authority);
        return this;
    }

    @Override
    public String path() {
        return get(HttpHeaderNames.PATH);
    }

    @Override
    public HttpHeaders path(String path) {
        requireNonNull(path, "path");
        set(HttpHeaderNames.PATH, path);
        return this;
    }

    @Nullable
    @Override
    public HttpStatus status() {
        final HttpStatus status = this.status;
        if (status != null) {
            return status;
        }

        final String statusStr = get(HttpHeaderNames.STATUS);
        if (statusStr == null) {
            return null;
        }

        try {
            return this.status = HttpStatus.valueOf(Integer.valueOf(statusStr));
        } catch (NumberFormatException ignored) {
            throw new IllegalStateException("invalid status: " + statusStr);
        }
    }

    @Override
    public HttpHeaders status(int statusCode) {
        final HttpStatus status = this.status = HttpStatus.valueOf(statusCode);
        set(HttpHeaderNames.STATUS, status.codeAsText());
        return this;
    }

    @Override
    public HttpHeaders status(HttpStatus status) {
        requireNonNull(status, "status");
        set(HttpHeaderNames.STATUS, status.codeAsText());
        return this;
    }

    @Nullable
    @Override
    public MediaType contentType() {
        final String contentTypeString = get(HttpHeaderNames.CONTENT_TYPE);
        if (contentTypeString == null) {
            return null;
        }

        final MediaType contentType = this.contentType;
        if (contentType != null && Ascii.equalsIgnoreCase(contentType.toString(), contentTypeString.trim())) {
            return contentType;
        }

        try {
            this.contentType = MediaType.parse(contentTypeString);
            return this.contentType;
        } catch (IllegalArgumentException unused) {
            // Invalid media type
            return null;
        }
    }

    @Override
    public HttpHeaders contentType(MediaType contentType) {
        requireNonNull(contentType, "contentType");
        this.contentType = contentType;
        return set(HttpHeaderNames.CONTENT_TYPE, contentType.toString());
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @Override
    public String toString() {
        final int size = size();
        if (size == 0) {
            return "[]";
        }

        final StringBuilder buf = new StringBuilder(size() * 16).append('[');
        String separator = "";
        for (AsciiString name : names()) {
            final List<String> values = getAll(name);
            for (int i = 0; i < values.size(); ++i) {
                buf.append(separator);
                buf.append(name).append('=').append(values.get(i));
            }
            separator = ", ";
        }
        return buf.append(']').toString();
    }

    @Override
    protected HeaderEntry<AsciiString, String> newHeaderEntry(int h, AsciiString name, String value,
                                                              HeaderEntry<AsciiString, String> next) {

        return super.newHeaderEntry(h, name.toLowerCase(), value, next);
    }
}
