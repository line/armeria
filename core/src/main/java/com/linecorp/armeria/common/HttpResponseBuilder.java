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

import java.util.Locale;
import java.util.Map.Entry;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * Builds a new {@link HttpResponse}.
 */
public final class HttpResponseBuilder extends AbstractHttpMessageBuilder {

    private final ResponseHeadersBuilder responseHeadersBuilder = ResponseHeaders.builder();

    HttpResponseBuilder() {}

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
        return status(HttpStatus.OK);
    }

    /**
     * Sets 201 Created to the status of this response.
     */
    public HttpResponseBuilder created() {
        return status(HttpStatus.CREATED);
    }

    /**
     * Sets 301 Moved Permanently to the status of this response.
     */
    public HttpResponseBuilder movedPermanently() {
        return status(HttpStatus.MOVED_PERMANENTLY);
    }

    /**
     * Sets 400 Bad Request to the status of this response.
     */
    public HttpResponseBuilder badRequest() {
        return status(HttpStatus.BAD_REQUEST);
    }

    /**
     * Sets 401 Unauthorized to the status of this response.
     */
    public HttpResponseBuilder unauthorized() {
        return status(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Sets 403 Forbidden to the status of this response.
     */
    public HttpResponseBuilder forbidden() {
        return status(HttpStatus.FORBIDDEN);
    }

    /**
     * Sets 404 Not Found to the status of this response.
     */
    public HttpResponseBuilder notFound() {
        return status(HttpStatus.NOT_FOUND);
    }

    /**
     * Sets 500 Internal Server Error to the status of this response.
     */
    public HttpResponseBuilder internalServerError() {
        return status(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Sets 502 Bad Gateway to the status of this response.
     */
    public HttpResponseBuilder badGateway() {
        return status(HttpStatus.BAD_GATEWAY);
    }

    /**
     * Sets the content as UTF_8 for this response.
     */
    @Override
    public HttpResponseBuilder content(String content) {
        return (HttpResponseBuilder) super.content(content);
    }

    /**
     * Sets the content for this response.
     */
    @Override
    public HttpResponseBuilder content(MediaType contentType, CharSequence content) {
        return (HttpResponseBuilder) super.content(contentType, content);
    }

    /**
     * Sets the content for this response.
     */
    @Override
    public HttpResponseBuilder content(MediaType contentType, String content) {
        return (HttpResponseBuilder) super.content(contentType, content);
    }

    /**
     * Sets the content as UTF_8 for this response. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @Override
    @FormatMethod
    public HttpResponseBuilder content(@FormatString String format, Object... content) {
        return (HttpResponseBuilder) super.content(format, content);
    }

    /**
     * Sets the content for this response. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @Override
    @FormatMethod
    public HttpResponseBuilder content(MediaType contentType, @FormatString String format,
                                       Object... content) {
        return (HttpResponseBuilder) super.content(contentType, format, content);
    }

    /**
     * Sets the content for this response. The {@code content} will be wrapped using
     * {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the response.
     */
    @Override
    public HttpResponseBuilder content(MediaType contentType, byte[] content) {
        return (HttpResponseBuilder) super.content(contentType, content);
    }

    /**
     * Sets the content for this response.
     */
    @Override
    public HttpResponseBuilder content(MediaType contentType, HttpData content) {
        return (HttpResponseBuilder) super.content(contentType, content);
    }

    /**
     * Sets the {@link Publisher} for this response.
     */
    @Override
    public HttpResponseBuilder content(MediaType contentType, Publisher<? extends HttpData> content) {
        return (HttpResponseBuilder) super.content(contentType, content);
    }

    /**
     * Sets the content for this response. The {@code content} is converted into JSON format
     * using the default {@link ObjectMapper}.
     */
    @Override
    public HttpResponseBuilder contentJson(Object content) {
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
    @Override
    public HttpResponseBuilder header(CharSequence name, Object value) {
        return (HttpResponseBuilder) super.header(name, value);
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
    @Override
    public HttpResponseBuilder headers(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        return (HttpResponseBuilder) super.headers(headers);
    }

    /**
     * Sets HTTP trailers for this response.
     */
    @Override
    public HttpResponseBuilder trailers(Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        return (HttpResponseBuilder) super.trailers(trailers);
    }

    @Override
    HttpHeadersBuilder headersBuilder() {
        return responseHeadersBuilder;
    }

    /**
     * Builds the response.
     */
    public HttpResponse build() {
        final ResponseHeaders responseHeaders = responseHeadersBuilder.build();
        final HttpHeadersBuilder trailers = httpTrailers();
        HttpData content = content();
        final Publisher<? extends HttpData> publisher = publisher();
        if (publisher == null) {
            if (content == null) {
                content = HttpData.empty();
            }
            if (trailers == null) {
                return HttpResponse.of(responseHeaders, content);
            } else {
                return HttpResponse.of(responseHeaders, content, trailers.build());
            }
        } else {
            if (trailers == null) {
                return HttpResponse.of(responseHeaders, publisher);
            } else {
                return HttpResponse.of(responseHeaders,
                                       StreamMessage.concat(publisher, StreamMessage.of(trailers.build())));
            }
        }
    }
}
