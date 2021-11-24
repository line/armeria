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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map.Entry;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

/**
 * Builds a new {@link HttpResponse}.
 */
public final class HttpResponseBuilder extends AbstractHttpMessageBuilder {

    private final ResponseHeadersBuilder responseHeadersBuilder = ResponseHeaders.builder();

    private final HttpHeadersBuilder httpTrailers = HttpHeaders.builder();

    /**
     * Sets the status for this response.
     */
    public HttpResponseBuilder status(int statusCode) {
        responseHeadersBuilder.status(HttpStatus.valueOf(statusCode));
        return this;
    }

    /**
     * Sets the status for this response.
     */
    public HttpResponseBuilder status(HttpStatus status) {
        requireNonNull(status, "status");
        responseHeadersBuilder.status(status);
        return this;
    }

    /**
     * Sets 200 OK to the status of this response.
     */
    public HttpResponseBuilder ok() {
        status(HttpStatus.OK);
        return this;
    }

    /**
     * Sets 201 Created to the status of this response.
     */
    public HttpResponseBuilder created() {
        status(HttpStatus.CREATED);
        return this;
    }

    /**
     * Sets 301 Moved Permanently to the status of this response.
     */
    public HttpResponseBuilder movedPermanently() {
        status(HttpStatus.MOVED_PERMANENTLY);
        return this;
    }

    /**
     * Sets 400 Bad Request to the status of this response.
     */
    public HttpResponseBuilder badRequest() {
        status(HttpStatus.BAD_REQUEST);
        return this;
    }

    /**
     * Sets 401 Unauthorized to the status of this response.
     */
    public HttpResponseBuilder unauthorized() {
        status(HttpStatus.UNAUTHORIZED);
        return this;
    }

    /**
     * Sets 403 Forbidden to the status of this response.
     */
    public HttpResponseBuilder forbidden() {
        status(HttpStatus.FORBIDDEN);
        return this;
    }

    /**
     * Sets 404 Not Found to the status of this response.
     */
    public HttpResponseBuilder notFound() {
        status(HttpStatus.NOT_FOUND);
        return this;
    }

    /**
     * Sets 500 Internal Server Error to the status of this response.
     */
    public HttpResponseBuilder internalServerError() {
        status(HttpStatus.INTERNAL_SERVER_ERROR);
        return this;
    }

    /**
     * Sets 502 Bad Gateway to the status of this response.
     */
    public HttpResponseBuilder badGateway() {
        status(HttpStatus.BAD_GATEWAY);
        return this;
    }

    /**
     * Sets the content as UTF_8 for this response.
     */
    @Override
    public HttpResponseBuilder content(String content) {
        return content(MediaType.PLAIN_TEXT_UTF_8, content);
    }

    /**
     * Sets the content for this response.
     */
    @Override
    public HttpResponseBuilder content(MediaType contentType, CharSequence content) {
        return content(contentType,
                       HttpData.of(contentType.charset(StandardCharsets.UTF_8),
                                   content));
    }

    /**
     * Sets the content for this response.
     */
    @Override
    public HttpResponseBuilder content(MediaType contentType, String content) {
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8),
                                                content));
    }

    /**
     * Sets the content as UTF_8 for this response. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @Override
    @FormatMethod
    public HttpResponseBuilder content(@FormatString String format, Object... content) {
        return content(MediaType.PLAIN_TEXT_UTF_8, format, content);
    }

    /**
     * Sets the content for this response. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @Override
    @FormatMethod
    public HttpResponseBuilder content(MediaType contentType, @FormatString String format,
                                       Object... content) {
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8),
                                                format, content));
    }

    /**
     * Sets the content for this response. The {@code content} will be wrapped using
     * {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the response.
     */
    @Override
    public HttpResponseBuilder content(MediaType contentType, byte[] content) {
        return content(contentType, HttpData.wrap(content));
    }

    /**
     * Sets the content for this response.
     */
    @Override
    public HttpResponseBuilder content(HttpData content) {
        return (HttpResponseBuilder) super.content(content);
    }

    /**
     * Sets the content for this response.
     */
    @Override
    public HttpResponseBuilder content(MediaType contentType, HttpData content) {
        requireNonNull(contentType, "contentType");
        responseHeadersBuilder.contentType(contentType);
        return (HttpResponseBuilder) super.content(content);
    }

    /**
     * Sets the {@link Publisher} for this response.
     */
    @Override
    public HttpResponseBuilder content(Publisher<? extends HttpData> content) {
        return (HttpResponseBuilder) super.content(content);
    }

    /**
     * Sets the {@link Publisher} for this response.
     */
    @Override
    public HttpResponseBuilder content(MediaType contentType, Publisher<? extends HttpData> content) {
        requireNonNull(contentType, "contentType");
        responseHeadersBuilder.contentType(contentType);
        return (HttpResponseBuilder) super.content(content);
    }

    /**
     * Sets the content for this response. The {@code content} that is converted into JSON
     * using the default {@link ObjectMapper}.
     */
    @Override
    public HttpResponseBuilder contentJson(Object content) {
        responseHeadersBuilder.contentType(MediaType.JSON);
        return (HttpResponseBuilder) super.contentJson(content);
    }

    /**
     * Sets a header for this response. For example:
     * <pre>{@code
     * HttpResponse.builder()
     *             .ok()
     *             .content("Hello, Armeria")
     *             .header("Server", "foo")
     *             .build();
     * }</pre>
     */
    public HttpResponseBuilder header(CharSequence name, Object value) {
        responseHeadersBuilder.setObject(requireNonNull(name, "name"),
                                         requireNonNull(value, "value"));
        return this;
    }

    /**
     * Sets multiple headers for this response. For example:
     * <pre>{@code
     * HttpResponse.builder()
     *             .ok()
     *             .content("Hello, Armeria")
     *             .headers(HttpHeaders.of("x-test-header", "foo", "Server", "baz"))
     *             .build();
     * }</pre>
     * @see HttpHeaders
     */
    public HttpResponseBuilder headers(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        requireNonNull(headers, "headers");
        responseHeadersBuilder.set(headers);
        return this;
    }

    /**
     * Sets HTTP trailers for this response.
     */
    public HttpResponseBuilder trailers(Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        requireNonNull(trailers, "trailers");
        httpTrailers.set(trailers);
        return this;
    }

    public HttpResponse build() {
        final ResponseHeaders responseHeaders = responseHeadersBuilder.build();
        final HttpHeaders trailers = httpTrailers.build();
        if (publisher == null) {
            if (content == null) {
                content = HttpData.empty();
            }
            return HttpResponse.of(responseHeaders, content, trailers);
        } else {
            return HttpResponse.of(responseHeaders, publisher);
        }
    }
}
