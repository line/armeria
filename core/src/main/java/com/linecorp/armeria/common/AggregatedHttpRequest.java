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

import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

/**
 * A complete HTTP request whose content is readily available as a single {@link HttpData}.
 */
public interface AggregatedHttpRequest extends AggregatedHttpMessage {

    // Note: Ensure we provide the same set of `of()` methods with the `of()` methods of
    //       HttpRequest for consistency.

    /**
     * Creates a new HTTP request with empty content.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     */
    static AggregatedHttpRequest of(HttpMethod method, String path) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        return of(RequestHeaders.of(method, path));
    }

    /**
     * Creates a new HTTP request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static AggregatedHttpRequest of(HttpMethod method, String path, MediaType mediaType, CharSequence content) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(content, "content");
        requireNonNull(mediaType, "mediaType");
        return of(method, path, mediaType,
                  HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content));
    }

    /**
     * Creates a new HTTP request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static AggregatedHttpRequest of(HttpMethod method, String path, MediaType mediaType, String content) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(content, "content");
        requireNonNull(mediaType, "mediaType");
        return of(method, path, mediaType,
                  HttpData.of(mediaType.charset(StandardCharsets.UTF_8), content));
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
    @FormatMethod
    static AggregatedHttpRequest of(HttpMethod method, String path, MediaType mediaType,
                                    @FormatString String format, Object... args) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        return of(method, path, mediaType,
                  HttpData.of(mediaType.charset(StandardCharsets.UTF_8), format, args));
    }

    /**
     * Creates a new HTTP request. The {@code content} will be wrapped using {@link HttpData#wrap(byte[])}, so
     * any changes made to {@code content} will be reflected in the request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static AggregatedHttpRequest of(HttpMethod method, String path, MediaType mediaType, byte[] content) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(method, path, mediaType, HttpData.wrap(content));
    }

    /**
     * Creates a new HTTP request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     */
    static AggregatedHttpRequest of(HttpMethod method, String path, MediaType mediaType, HttpData content) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        return of(method, path, mediaType, content, HttpHeaders.of());
    }

    /**
     * Creates a new HTTP request.
     *
     * @param method the HTTP method of the request
     * @param path the path of the request
     * @param mediaType the {@link MediaType} of the request content
     * @param content the content of the request
     * @param trailers the HTTP trailers
     */
    static AggregatedHttpRequest of(HttpMethod method, String path, MediaType mediaType,
                                    HttpData content, HttpHeaders trailers) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(mediaType, "mediaType");
        requireNonNull(content, "content");
        requireNonNull(trailers, "trailers");
        return of(RequestHeaders.builder(method, path).contentType(mediaType).build(),
                  content, trailers);
    }

    /**
     * Creates a new HTTP request with empty content.
     *
     * @param headers the HTTP request headers
     */
    static AggregatedHttpRequest of(RequestHeaders headers) {
        requireNonNull(headers, "headers");
        return of(headers, HttpData.empty(), HttpHeaders.of());
    }

    /**
     * Creates a new HTTP request.
     *
     * @param headers the HTTP request headers
     * @param content the content of the request
     */
    static AggregatedHttpRequest of(RequestHeaders headers, HttpData content) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        return of(headers, content, HttpHeaders.of());
    }

    /**
     * Creates a new HTTP request.
     *
     * @param headers the HTTP request headers
     * @param content the content of the request
     * @param trailers the HTTP trailers
     */
    static AggregatedHttpRequest of(RequestHeaders headers, HttpData content, HttpHeaders trailers) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailers, "trailers");

        final RequestHeadersBuilder builder = headers.toBuilder();
        if (content.isEmpty()) {
            builder.remove(CONTENT_LENGTH);
        } else {
            builder.contentLength(content.length());
        }
        headers = builder.build();
        return new DefaultAggregatedHttpRequest(headers, content, trailers);
    }

    /**
     * Returns the {@link RequestHeaders}.
     */
    @Override
    RequestHeaders headers();

    /**
     * Returns the URI of this request. This method is a shortcut for {@code headers().uri()}.
     */
    default URI uri() {
        return headers().uri();
    }

    /**
     * Returns the scheme of this request. This method is a shortcut for {@code headers().scheme()}.
     */
    @Nullable
    default String scheme() {
        return headers().scheme();
    }

    /**
     * Returns the method of this request. This method is a shortcut for {@code headers().method()}.
     */
    default HttpMethod method() {
        return headers().method();
    }

    /**
     * Returns the path of this request. This method is a shortcut for {@code headers().path()}.
     */
    default String path() {
        return headers().path();
    }

    /**
     * Returns the authority of this request. This method is a shortcut for {@code headers().authority()}.
     */
    @Nullable
    default String authority() {
        return headers().authority();
    }

    /**
     * Returns a list of {@link LanguageRange}s that are specified in {@link HttpHeaderNames#ACCEPT_LANGUAGE}
     * in the order of client-side preferences. If the client does not send the header, this will contain only a
     * wild card {@link LanguageRange}.
     */
    @Nullable
    default List<LanguageRange> acceptLanguages() {
        return headers().acceptLanguages();
    }

    /**
     * Matches the {@link Locale}s supported by the server to
     * the {@link HttpHeaderNames#ACCEPT_LANGUAGE} and returning the best match
     * according to client preference. It does this via <s>Basic Filter</s>ing each
     * {@link LanguageRange} and picking the first match. This is the "classic"
     * algorithm described in
     * <a href="https://datatracker.ietf.org/doc/html/rfc2616#section-14.4">RFC2616 Accept-Language (obsoleted)</a>
     * and also referenced in <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.5">RFC7231 Accept-Language</a>.
     * @param supportedLocales an {@link Iterable} of {@link Locale}s supported by the server.
     * @return The best matching {@link Locale} or {@code null} if no {@link Locale} matches.
     */
    @Nullable
    default Locale selectLocale(Iterable<Locale> supportedLocales) {
        return headers().selectLocale(supportedLocales);
    }

    /**
     * Matches the {@link Locale}s supported by the server to
     * the {@link HttpHeaderNames#ACCEPT_LANGUAGE} and returning the best match
     * according to client preference. It does this via <s>Basic Filter</s>ing each
     * {@link LanguageRange} and picking the first match. This is the "classic"
     * algorithm described in
     * <a href="https://datatracker.ietf.org/doc/html/rfc2616#section-14.4">RFC2616 Accept-Language (obsoleted)</a>
     * and also referenced in <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.5">RFC7231 Accept-Language</a>.
     * @param supportedLocales {@link Locale}s supported by the server.
     * @return The best matching {@link Locale} or {@code null} if no {@link Locale} matches.
     */
    @Nullable
    default Locale selectLocale(Locale... supportedLocales) {
        return selectLocale(ImmutableList.copyOf(requireNonNull(supportedLocales, "supportedLocales")));
    }

    /**
     * Converts this request into a new complete {@link HttpRequest}.
     *
     * @return the new {@link HttpRequest} converted from this request.
     */
    default HttpRequest toHttpRequest() {
        return HttpRequest.of(headers(), content(), trailers());
    }
}
