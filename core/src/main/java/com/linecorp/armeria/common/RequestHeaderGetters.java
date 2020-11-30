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

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

/**
 * Provides the getter methods to {@link RequestHeaders} and {@link RequestHeadersBuilder}.
 *
 * @see ResponseHeaderGetters
 */
interface RequestHeaderGetters extends HttpHeaderGetters {

    /**
     * Returns the request URI generated from the {@code ":scheme"}, {@code ":authority"} and {@code ":path"}
     * headers.
     *
     * @throws IllegalStateException if any of the required headers do not exist or
     *                               the resulting URI is not valid.
     */
    URI uri();

    /**
     * Returns the value of the {@code ":method"} header as an {@link HttpMethod}.
     * {@link HttpMethod#UNKNOWN} is returned if the value is not defined in {@link HttpMethod}.
     *
     * @throws IllegalStateException if there is no such header.
     */
    HttpMethod method();

    /**
     * Returns the value of the {@code ":path"} header.
     *
     * @throws IllegalStateException if there is no such header.
     */
    String path();

    /**
     * Returns the value of the {@code ":scheme"} header or {@code null} if there is no such header.
     */
    @Nullable
    String scheme();

    /**
     * Returns the value of the {@code ":authority"} for HTTP/2 request or {@code "Host"} header for HTTP/1.1.
     * {@code null} if there is no such headers.
     */
    @Nullable
    String authority();

    /**
     * Returns a list of {@link LanguageRange}s that are specified in {@link HttpHeaderNames#ACCEPT_LANGUAGE}
     * in the order of client preferences.
     * @return All {@link LanguageRange}s of all matching headers or {@code null} if there is a parsing error
     *         or if there is no header.
     */
    @Nullable
    List<LanguageRange> acceptLanguages();

    /**
     * Matches the {@link Locale}s supported by the server to
     * the {@link HttpHeaderNames#ACCEPT_LANGUAGE} and returns the best match
     * according to client preference. It does this via <s>Basic Filter</s>ing each
     * {@link LanguageRange} and picking the first match. This is the "classic"
     * algorithm described in
     * <a href="https://tools.ietf.org/html/rfc2616#section-14.4">RFC2616 Accept-Language (obsoleted)</a>
     * and also referenced in <a href="https://tools.ietf.org/html/rfc7231#section-5.3.5">RFC7231 Accept-Language</a>.
     * <p/>
     * See also {@link Locale#lookup} for another algorithm.
     * @param supportedLocales a {@link Iterable} of {@link Locale}s supported by the server.
     * @return The best matching {@link Locale} or {@code null} if no locale matches.
     */
    @Nullable
    Locale selectLocale(Iterable<Locale> supportedLocales);

    /**
     * Matches the {@link Locale}s supported by the server to
     * the {@link HttpHeaderNames#ACCEPT_LANGUAGE} and returns the best match
     * according to client preference. It does this via <s>Basic Filter</s>ing each
     * {@link LanguageRange} and picking the first match. This is the "classic"
     * algorithm described in
     * <a href="https://tools.ietf.org/html/rfc2616#section-14.4">RFC2616 Accept-Language (obsoleted)</a>
     * and also referenced in <a href="https://tools.ietf.org/html/rfc7231#section-5.3.5">RFC7231 Accept-Language</a>.
     * <p/>
     * See also {@link Locale#lookup} for another algorithm.
     * @param supportedLocales {@link Locale}s supported by the server.
     * @return The best matching {@link Locale} or {@code null} if no locale matches.
     */
    @Nullable
    default Locale selectLocale(Locale... supportedLocales) {
        return selectLocale(ImmutableList.copyOf(requireNonNull(supportedLocales, "supportedLocales")));
    }
}
