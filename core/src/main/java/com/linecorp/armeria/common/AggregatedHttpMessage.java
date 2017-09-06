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

import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.isContentAlwaysEmpty;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.isContentAlwaysEmptyWithValidation;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;

import com.google.common.collect.ImmutableList;

/**
 * A complete HTTP message whose content is readily available as a single {@link HttpData}. It can be an
 * HTTP request or an HTTP response depending on what header values it contains. For example, having a
 * {@link HttpHeaderNames#STATUS} header could mean it is an HTTP response.
 */
public interface AggregatedHttpMessage {

    // Note: Ensure we provide the same set of `of()` methods with the `of()` methods of
    //       HttpRequest for consistency.

    /**
     * Creates a new HTTP request with empty content.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     */
    static AggregatedHttpMessage of(HttpMethod method, String path) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        return of(HttpHeaders.of(method, path));
    }

    /**
     * Creates a new HTTP request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static AggregatedHttpMessage of(HttpMethod method, String path, MediaType mediaType, String content) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(content, "content");
        requireNonNull(mediaType, "mediaType");
        return of(method, path,
                  mediaType, content.getBytes(mediaType.charset().orElse(StandardCharsets.UTF_8)));
    }

    /**
     * Creates a new HTTP request. The content of the request is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param format {@linkplain Formatter the format string} of the request content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    static AggregatedHttpMessage of(HttpMethod method, String path, MediaType mediaType,
                                    String format, Object... args) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        return of(method,
                  path,
                  mediaType,
                  String.format(Locale.ENGLISH, format, args).getBytes(
                          mediaType.charset().orElse(StandardCharsets.UTF_8)));
    }

    /**
     * Creates a new HTTP request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static AggregatedHttpMessage of(HttpMethod method, String path, MediaType mediaType, byte[] content) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(method, path, mediaType, HttpData.of(content));
    }

    /**
     * Creates a new HTTP request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     * @param offset the start offset of {@code content}
     * @param length the length of {@code content}
     */
    static AggregatedHttpMessage of(
            HttpMethod method, String path, MediaType mediaType, byte[] content, int offset, int length) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(method, path, mediaType, HttpData.of(content, offset, length));
    }

    /**
     * Creates a new HTTP request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static AggregatedHttpMessage of(HttpMethod method, String path, MediaType mediaType, HttpData content) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(method, path, mediaType, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new HTTP request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     * @param trailingHeaders the trailing HTTP headers
     */
    static AggregatedHttpMessage of(HttpMethod method, String path, MediaType mediaType,
                                    HttpData content, HttpHeaders trailingHeaders) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");
        return of(HttpHeaders.of(method, path).setObject(CONTENT_TYPE, mediaType), content, trailingHeaders);
    }

    // Note: Ensure we provide the same set of `of()` methods with the `of()` and `respond()` methods of
    //       HttpResponse and HttpResponseWriter for consistency.

    /**
     * Creates a new HTTP response.
     *
     * @param statusCode the HTTP status code
     */
    static AggregatedHttpMessage of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    /**
     * Creates a new HTTP response.
     *
     * @param status the HTTP status
     */
    static AggregatedHttpMessage of(HttpStatus status) {
        requireNonNull(status, "status");
        if (isContentAlwaysEmpty(status)) {
            return of(HttpHeaders.of(status));
        } else {
            return of(status, MediaType.PLAIN_TEXT_UTF_8, status.toHttpData());
        }
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static AggregatedHttpMessage of(HttpStatus status, MediaType mediaType, String content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(status,
                  mediaType, content.getBytes(mediaType.charset().orElse(StandardCharsets.UTF_8)));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}. The content of the response is
     * formatted by {@link String#format(Locale, String, Object...)} with
     * {@linkplain Locale#ENGLISH English locale}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     */
    static AggregatedHttpMessage of(HttpStatus status, MediaType mediaType, String format, Object... args) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        return of(status,
                  mediaType,
                  String.format(Locale.ENGLISH, format, args).getBytes(
                          mediaType.charset().orElse(StandardCharsets.UTF_8)));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static AggregatedHttpMessage of(HttpStatus status, MediaType mediaType, byte[] content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(status, mediaType, HttpData.of(content));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     * @param offset the start offset of {@code content}
     * @param length the length of {@code content}
     */
    static AggregatedHttpMessage of(
            HttpStatus status, MediaType mediaType, byte[] content, int offset, int length) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(status, mediaType, HttpData.of(content, offset, length));
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     */
    static AggregatedHttpMessage of(HttpStatus status, MediaType mediaType, HttpData content) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(status, mediaType, content, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new HTTP response of the specified {@link HttpStatus}.
     *
     * @param mediaType the {@link MediaType} of the response content
     * @param content the content of the response
     * @param trailingHeaders the trailing HTTP headers
     */
    static AggregatedHttpMessage of(HttpStatus status, MediaType mediaType, HttpData content,
                                    HttpHeaders trailingHeaders) {
        requireNonNull(status, "status");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");

        final HttpHeaders headers =
                HttpHeaders.of(status)
                           .setObject(CONTENT_TYPE, mediaType)
                           .setInt(CONTENT_LENGTH, content.length());

        return of(headers, content, trailingHeaders);
    }

    /**
     * Creates a new HTTP message with empty content.
     *
     * @param headers the HTTP headers
     */
    static AggregatedHttpMessage of(HttpHeaders headers) {
        requireNonNull(headers, "headers");
        return of(headers, HttpData.EMPTY_DATA, HttpHeaders.EMPTY_HEADERS);
    }

    /**
     * Creates a new HTTP message.
     *
     * @param headers the HTTP headers
     * @param content the content of the HTTP message
     */
    static AggregatedHttpMessage of(HttpHeaders headers, HttpData content) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
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
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");
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

        // Set the 'content-length' header if possible.
        final HttpStatus status = headers.status();
        if (status != null) { // Response
            if (!isContentAlwaysEmptyWithValidation(status, content, trailingHeaders) &&
                !headers.contains(CONTENT_LENGTH)) {
                // But do not overwrite the 'content-length' header because a response to a HEAD request
                // will have no content even if it has non-zero content-length header.
                headers.setInt(CONTENT_LENGTH, content.length());
            }
        } else { // Request
            if (content.isEmpty()) {
                headers.remove(CONTENT_LENGTH);
            } else {
                headers.setInt(CONTENT_LENGTH, content.length());
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

    /**
     * Converts this message into a new complete {@link HttpRequest}.
     *
     * @return the converted {@link HttpRequest} whose stream is complete
     * @throws IllegalStateException if this message is not a request
     */
    default HttpRequest toHttpRequest() {
        final HttpHeaders headers = headers();

        // From the section 8.1.2.3 of RFC 7540:
        //// All HTTP/2 requests MUST include exactly one valid value for the :method, :scheme, and :path
        //// pseudo-header fields, unless it is a CONNECT request (Section 8.3)
        // NB: ':scheme' will be filled when a request is written.
        if (headers.method() == null) {
            throw new IllegalStateException("not a request (missing :method)");
        }
        if (headers.path() == null) {
            throw new IllegalStateException("not a request (missing :path)");
        }

        final DefaultHttpRequest req = new DefaultHttpRequest(headers);
        final HttpData content = content();
        if (!content.isEmpty()) {
            req.write(content);
        }

        final HttpHeaders trailingHeaders = trailingHeaders();
        if (!trailingHeaders.isEmpty()) {
            req.write(trailingHeaders);
        }

        req.close();
        return req;
    }

    /**
     * Converts this message into a new complete {@link HttpResponse}.
     *
     * @return the converted {@link HttpResponse} whose stream is complete
     * @throws IllegalStateException if this message is not a response.
     */
    default HttpResponse toHttpResponse() {
        final DefaultHttpResponse res = new DefaultHttpResponse();
        final List<HttpHeaders> informationals = informationals();
        final HttpHeaders headers = headers();

        // From the section 8.1.2.4 of RFC 7540:
        //// For HTTP/2 responses, a single :status pseudo-header field is defined that carries the HTTP status
        //// code field (see [RFC7231], Section 6). This pseudo-header field MUST be included in all responses;
        //// otherwise, the response is malformed (Section 8.1.2.6).
        if (headers.status() == null) {
            throw new IllegalStateException("not a response (missing :status)");
        }

        if (!informationals.isEmpty()) {
            informationals.forEach(res::write);
        }

        res.write(headers);

        final HttpData content = content();
        if (!content.isEmpty()) {
            res.write(content);
        }

        final HttpHeaders trailingHeaders = trailingHeaders();
        if (!trailingHeaders.isEmpty()) {
            res.write(trailingHeaders);
        }
        res.close();
        return res;
    }
}
