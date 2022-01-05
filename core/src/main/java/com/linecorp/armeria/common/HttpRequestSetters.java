/*
 * Copyright 2020 LINE Corporation
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

/**
 * Sets properties for building a {@link HttpRequest}.
 */
public interface HttpRequestSetters {

    /**
     * Shortcut to set GET method and path.
     */
    HttpRequestSetters get(String path);

    /**
     * Shortcut to set POST method and path.
     */
    HttpRequestSetters post(String path);

    /**
     * Shortcut to set PUT method and path.
     */
    HttpRequestSetters put(String path);

    /**
     * Shortcut to set DELETE method and path.
     */
    HttpRequestSetters delete(String path);

    /**
     * Shortcut to set PATCH method and path.
     */
    HttpRequestSetters patch(String path);

    /**
     * Shortcut to set OPTIONS method and path.
     */
    HttpRequestSetters options(String path);

    /**
     * Shortcut to set HEAD method and path.
     */
    HttpRequestSetters head(String path);

    /**
     * Shortcut to set TRACE method and path.
     */
    HttpRequestSetters trace(String path);

    /**
     * Sets the method for this request.
     *
     * @see HttpMethod
     */
    HttpRequestSetters method(HttpMethod method);

    /**
     * Sets the path for this request.
     */
    HttpRequestSetters path(String path);

    /**
     * Sets the content as UTF_8 for this request.
     */
    HttpRequestSetters content(String content);

    /**
     * Sets the content for this request.
     */
    HttpRequestSetters content(MediaType contentType, CharSequence content);

    /**
     * Sets the content for this request.
     */
    HttpRequestSetters content(MediaType contentType, String content);

    /**
     * Sets the content as UTF_8 for this request. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @FormatMethod
    HttpRequestSetters content(@FormatString String format, Object... content);

    /**
     * Sets the content for this request. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @FormatMethod
    HttpRequestSetters content(MediaType contentType, @FormatString String format,
                                      Object... content);

    /**
     * Sets the content for this request. The {@code content} will be wrapped using
     * {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the request.
     */
    HttpRequestSetters content(MediaType contentType, byte[] content);

    /**
     * Sets the content for this request.
     */
    HttpRequestSetters content(MediaType contentType, HttpData content);

    /**
     * Sets the {@link Publisher} for this request.
     */
    HttpRequestSetters content(MediaType contentType, Publisher<? extends HttpData> content);

    /**
     * Sets the content for this request. The {@code content} is converted into JSON format
     * using the default {@link ObjectMapper}.
     */
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
    HttpRequestSetters headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers);

    /**
     * Sets HTTP trailers for this request.
     */
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
    HttpRequestSetters pathParams(Map<String, ?> pathParams);

    /**
     * Disables path parameters substitution. If path parameter is not disabled and a parameter's, specified
     * using {@code {}} or {@code :}, value is not found, an {@link IllegalStateException} is thrown.
     */
    HttpRequestSetters disablePathParams();

    /**
     * Sets a query param for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/endpoint")
     *            .queryParam("foo", "bar")
     *            .build(); // GET `/endpoint?foo=bar`
     * }</pre>
     */
    HttpRequestSetters queryParam(String name, Object value);

    /**
     * Sets multiple query params for this request. For example:
     * <pre>{@code
     * HttpRequest.builder()
     *            .get("/endpoint")
     *            .queryParams(QueryParams.of("from", "foo", "limit", 10))
     *            .build(); // GET `/endpoint?from=foo&limit=10`
     * }</pre>
     *
     * @see QueryParams
     */
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
    HttpRequestSetters cookies(Iterable<? extends Cookie> cookies);
}
