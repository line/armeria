/*
 * Copyright 2022 LINE Corporation
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

import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Sets properties for building an {@link HttpRequest}.
 */
@UnstableApi
public interface HttpRequestSetters extends RequestMethodSetters, PathAndQueryParamSetters, HttpMessageSetters {

    /**
     * Sets the content as UTF-8 for this request.
     */
    @Override
    HttpRequestSetters content(String content);

    /**
     * Sets the content for this request.
     */
    @Override
    HttpRequestSetters content(MediaType contentType, CharSequence content);

    /**
     * Sets the content for this request.
     */
    @Override
    HttpRequestSetters content(MediaType contentType, String content);

    /**
     * Sets the content as UTF-8 for this request. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @Override
    @FormatMethod
    HttpRequestSetters content(@FormatString String format, Object... content);

    /**
     * Sets the content for this request. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @Override
    @FormatMethod
    HttpRequestSetters content(MediaType contentType, @FormatString String format,
                               Object... content);

    /**
     * Sets the content for this request. The {@code content} will be wrapped using
     * {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the request.
     */
    @Override
    HttpRequestSetters content(MediaType contentType, byte[] content);

    /**
     * Sets the content for this request.
     */
    @Override
    HttpRequestSetters content(MediaType contentType, HttpData content);

    /**
     * Sets the {@link Publisher} for this request.
     */
    @Override
    HttpRequestSetters content(Publisher<? extends HttpData> content);

    /**
     * Sets the {@link Publisher} for this request.
     */
    @Override
    HttpRequestSetters content(MediaType contentType, Publisher<? extends HttpData> content);

    /**
     * Sets the content for this request. The {@code content} is converted into JSON format
     * using the default {@link ObjectMapper}.
     */
    @Override
    HttpRequestSetters contentJson(Object content);

    /**
     * Adds a header for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/")
     *            .header("authorization", "foo")
     *            .build();
     * }</pre>
     */
    @Override
    HttpRequestSetters header(CharSequence name, Object value);

    /**
     * Adds multiple headers for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/")
     *            .headers(HttpHeaders.of("authorization", "foo", "bar", "baz"))
     *            .build();
     * }</pre>
     *
     * @see HttpHeaders
     */
    @Override
    HttpRequestSetters headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers);

    /**
     * Sets HTTP trailers for this request.
     */
    @Override
    HttpRequestSetters trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers);

    /**
     * Sets a path param for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/{foo}")
     *            .pathParam("foo", "bar")
     *            .build(); // GET `/bar`
     * }</pre>
     */
    @Override
    HttpRequestSetters pathParam(String name, Object value);

    /**
     * Sets multiple path params for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/{foo}/:bar")
     *            .pathParams(Map.of("foo", 1, "bar", 2))
     *            .build(); // GET `/1/2`
     * }</pre>
     */
    @Override
    HttpRequestSetters pathParams(Map<String, ?> pathParams);

    /**
     * Disables path parameters substitution. If path parameter is not disabled and a parameter's, specified
     * using {@code {}} or {@code :}, value is not found, an {@link IllegalStateException} is thrown.
     */
    @Override
    HttpRequestSetters disablePathParams();

    /**
     * Adds a query param for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/endpoint")
     *            .queryParam("foo", "bar")
     *            .build(); // GET `/endpoint?foo=bar`
     * }</pre>
     */
    @Override
    HttpRequestSetters queryParam(String name, Object value);

    /**
     * Adds multiple query params for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/endpoint")
     *            .queryParams(QueryParams.of("from", "foo", "limit", 10))
     *            .build(); // GET `/endpoint?from=foo&limit=10`
     * }</pre>
     *
     * @see QueryParams
     */
    @Override
    HttpRequestSetters queryParams(Iterable<? extends Entry<? extends String, String>> queryParams);

    /**
     * Sets a cookie for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/")
     *            .cookie(Cookie.ofSecure("cookie", "foo"))
     *            .build();
     * }</pre>
     *
     * @see Cookie
     */
    @Override
    HttpRequestSetters cookie(Cookie cookie);

    /**
     * Sets multiple cookies for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/")
     *            .cookies(Cookies.ofSecure(Cookie.ofSecure("cookie1", "foo"),
     *                                      Cookie.ofSecure("cookie2", "bar")))
     *            .build();
     * }</pre>
     *
     * @see Cookies
     */
    @Override
    HttpRequestSetters cookies(Iterable<? extends Cookie> cookies);
}
