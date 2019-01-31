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

import static java.util.Objects.requireNonNull;

import java.util.Iterator;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import io.netty.handler.codec.Headers;
import io.netty.util.AsciiString;

/**
 * HTTP/2 headers.
 */
@JsonSerialize(using = HttpHeadersJsonSerializer.class)
@JsonDeserialize(using = HttpHeadersJsonDeserializer.class)
public interface HttpHeaders extends HttpObject, Headers<AsciiString, String, HttpHeaders> {

    /**
     * An immutable empty HTTP/2 headers.
     */
    HttpHeaders EMPTY_HEADERS = new DefaultHttpHeaders(false, 0).asImmutable();

    /**
     * Returns new empty HTTP headers.
     */
    static HttpHeaders of() {
        return new DefaultHttpHeaders();
    }

    /**
     * Returns new HTTP request headers.
     */
    static HttpHeaders of(HttpMethod method, String path) {
        return new DefaultHttpHeaders().method(method).path(path);
    }

    /**
     * Returns new HTTP response headers.
     */
    static HttpHeaders of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Returns new HTTP response headers.
     */
    static HttpHeaders of(HttpStatus status) {
        return new DefaultHttpHeaders().status(status);
    }

    /**
     * Returns new HTTP headers with a single entry.
     */
    static HttpHeaders of(AsciiString name, String value) {
        return new DefaultHttpHeaders().add(name, value);
    }

    /**
     * Returns new HTTP headers with two entries.
     */
    static HttpHeaders of(AsciiString name1, String value1, AsciiString name2, String value2) {
        return new DefaultHttpHeaders().add(name1, value1).add(name2, value2);
    }

    /**
     * Returns new HTTP headers with three entries.
     */
    static HttpHeaders of(AsciiString name1, String value1, AsciiString name2, String value2,
                          AsciiString name3, String value3) {

        return new DefaultHttpHeaders().add(name1, value1).add(name2, value2)
                                       .add(name3, value3);
    }

    /**
     * Returns new HTTP headers with four entries.
     */
    static HttpHeaders of(AsciiString name1, String value1, AsciiString name2, String value2,
                          AsciiString name3, String value3, AsciiString name4, String value4) {

        return new DefaultHttpHeaders().add(name1, value1).add(name2, value2)
                                       .add(name3, value3).add(name4, value4);
    }

    /**
     * Returns a copy of the specified {@link HttpHeaders}.
     */
    static HttpHeaders copyOf(HttpHeaders headers) {
        return of().set(requireNonNull(headers, "headers"));
    }

    /**
     * Returns an iterator over all HTTP/2 headers. The iteration order is as follows:
     *   1. All pseudo headers (order not specified).
     *   2. All non-pseudo headers (in insertion order).
     */
    @Override
    Iterator<Entry<AsciiString, String>> iterator();

    /**
     * Gets the {@link HttpHeaderNames#METHOD} header or {@code null} if there is no such header.
     * {@link HttpMethod#UNKNOWN} is returned if the value of the {@link HttpHeaderNames#METHOD} header is
     * not defined in {@link HttpMethod}.
     */
    @Nullable
    HttpMethod method();

    /**
     * Sets the {@link HttpHeaderNames#METHOD} header.
     */
    HttpHeaders method(HttpMethod method);

    /**
     * Gets the {@link HttpHeaderNames#SCHEME} header or {@code null} if there is no such header.
     */
    @Nullable
    String scheme();

    /**
     * Sets the {@link HttpHeaderNames#SCHEME} header.
     */
    HttpHeaders scheme(String scheme);

    /**
     * Gets the {@link HttpHeaderNames#AUTHORITY} header or {@code null} if there is no such header.
     */
    @Nullable
    String authority();

    /**
     * Sets the {@link HttpHeaderNames#AUTHORITY} header.
     */
    HttpHeaders authority(String authority);

    /**
     * Gets the {@link HttpHeaderNames#PATH} header or {@code null} if there is no such header.
     */
    @Nullable
    String path();

    /**
     * Sets the {@link HttpHeaderNames#PATH} header.
     */
    HttpHeaders path(String path);

    /**
     * Gets the {@link HttpHeaderNames#STATUS} header or {@code null} if there is no such header.
     */
    @Nullable
    HttpStatus status();

    /**
     * Sets the {@link HttpHeaderNames#STATUS} header.
     */
    HttpHeaders status(int statusCode);

    /**
     * Sets the {@link HttpHeaderNames#STATUS} header.
     */
    HttpHeaders status(HttpStatus status);

    /**
     * Returns the value of the {@code 'content-type'} header.
     * @return the valid header value if present. {@code null} otherwise.
     */
    @Nullable
    MediaType contentType();

    /**
     * Sets the {@link HttpHeaderNames#CONTENT_TYPE} header.
     */
    HttpHeaders contentType(MediaType mediaType);

    /**
     * Copies the entries missing in this headers from the specified {@link Headers}.
     * This method is a shortcut of the following code:
     * <pre>{@code
     * headers.names().forEach(name -> {
     *      if (!contains(name)) {
     *          set(name, headers.getAll(name));
     *      }
     * });
     * }</pre>
     */
    default HttpHeaders setAllIfAbsent(Headers<AsciiString, String, ?> headers) {
        requireNonNull(headers, "headers");
        if (!headers.isEmpty()) {
            headers.names().forEach(name -> {
                if (!contains(name)) {
                    set(name, headers.getAll(name));
                }
            });
        }
        return this;
    }

    /**
     * Returns the immutable view of this headers.
     */
    default HttpHeaders asImmutable() {
        return new ImmutableHttpHeaders(this);
    }

    /**
     * Returns whether this is immutable or not.
     */
    default boolean isImmutable() {
        return this instanceof ImmutableHttpHeaders;
    }

    /**
     * Returns a mutable copy of this headers.
     * If it is already mutable, it returns {@code this}.
     */
    default HttpHeaders toMutable() {
        return this;
    }
}
