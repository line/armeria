/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.internal.http.ArmeriaHttpUtil;

/**
 * A complete HTTP message whose content is readily available as a single {@link HttpData}. It can be an
 * HTTP request or an HTTP response depending on what header values it contains. For example, having a
 * {@link HttpHeaderNames#STATUS} header could mean it is an HTTP response.
 */
public interface AggregatedHttpMessage {

    /**
     * Creates a new HTTP request with empty content.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     */
    static AggregatedHttpMessage of(HttpMethod method, String path) {
        return of(HttpHeaders.of(method, path));
    }

    /**
     * Creates a new HTTP request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param content the content of the request
     */
    static AggregatedHttpMessage of(HttpMethod method, String path, HttpData content) {
        return of(HttpHeaders.of(method, path), content);
    }

    /**
     * Creates a new HTTP response with empty content.
     *
     * @param statusCode the HTTP status code
     */
    static AggregatedHttpMessage of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Creates a new HTTP response with empty content.
     *
     * @param status the HTTP status
     */
    static AggregatedHttpMessage of(HttpStatus status) {
        return of(HttpHeaders.of(status));
    }

    /**
     * Creates a new HTTP response.
     *
     * @param status the HTTP status
     * @param content the content of the HTTP response
     */
    static AggregatedHttpMessage of(HttpStatus status, HttpData content) {
        return of(HttpHeaders.of(status), content);
    }

    /**
     * Creates a new HTTP message with empty content.
     *
     * @param headers the HTTP headers
     */
    static AggregatedHttpMessage of(HttpHeaders headers) {
        return of(headers, HttpData.EMPTY_DATA, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new HTTP message.
     *
     * @param headers the HTTP headers
     * @param content the content of the HTTP message
     */
    static AggregatedHttpMessage of(HttpHeaders headers, HttpData content) {
        return of(headers, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new HTTP message.
     *
     * @param headers the HTTP headers
     * @param content the content of the HTTP message
     * @param trailingHeaders the trailing HTTP headers
     */
    static AggregatedHttpMessage of(HttpHeaders headers, HttpData content, HttpHeaders trailingHeaders) {
        return of(Collections.emptyList(), headers, content, trailingHeaders);
    }

    /**
     * Creates a new HTTP message.
     *
     * @param informationals the informational class (1xx) HTTP headers
     * @param headers the HTTP headers
     * @param content the content of the HTTP message
     * @param trailingHeaders the trailing HTTP headers
     */
    static AggregatedHttpMessage of(Iterable<HttpHeaders> informationals, HttpHeaders headers,
                                    HttpData content, HttpHeaders trailingHeaders) {

        requireNonNull(informationals, "informationals");
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");

        // Set the 'content-length' header if possible, but do not overwrite because a response to
        // a HEAD request will have no content but still have non-zero content-length header.
        final HttpStatus status = headers.status();
        if (status != null && !ArmeriaHttpUtil.isContentAlwaysEmpty(status)) {
            if (!headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                headers.setInt(HttpHeaderNames.CONTENT_LENGTH, content.length());
            }
        }

        return new DefaultAggregatedHttpMessage(ImmutableList.copyOf(informationals),
                                                headers, content, trailingHeaders);
    }

    /**
     * Returns the informational class (1xx) HTTP headers.
     */
    List<HttpHeaders> informationals();

    /**
     * Returns the HTTP headers.
     */
    HttpHeaders headers();

    /**
     * Returns the trailing HTTP headers.
     */
    HttpHeaders trailingHeaders();

    /**
     * Returns the content of this message.
     */
    HttpData content();

    /**
     * Returns the {@link HttpHeaderNames#SCHEME SCHEME} of this message.
     *
     * @return the scheme, or {@code null} if there's no such header
     */
    default String scheme() {
        return headers().scheme();
    }

    /**
     * Returns the {@link HttpHeaderNames#METHOD METHOD} of this message.
     *
     * @return the method, or {@code null} if there's no such header
     */
    default HttpMethod method() {
        return headers().method();
    }

    /**
     * Returns the {@link HttpHeaderNames#PATH PATH} of this message.
     *
     * @return the path, or {@code null} if there's no such header
     */
    default String path() {
        return headers().path();
    }

    /**
     * Returns the {@link HttpHeaderNames#AUTHORITY AUTHORITY} of this message, in the form of
     * {@code "hostname:port"}.
     *
     * @return the authority, or {@code null} if there's no such header
     */
    default String authority() {
        return headers().authority();
    }

    /**
     * Returns the {@link HttpHeaderNames#STATUS STATUS} of this message.
     *
     * @return the status, or {@code null} if there's no such header
     */
    default HttpStatus status() {
        return headers().status();
    }
}
