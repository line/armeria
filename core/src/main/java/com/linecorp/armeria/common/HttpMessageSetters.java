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
import java.util.Map.Entry;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Sets properties for building an {@link HttpMessage}.
 */
@UnstableApi
public interface HttpMessageSetters {

    /**
     * Sets the content as UTF-8 for this message.
     */
    HttpMessageSetters content(String content);

    /**
     * Sets the content for this message.
     */
    HttpMessageSetters content(MediaType contentType, CharSequence content);

    /**
     * Sets the content for this message.
     */
    HttpMessageSetters content(MediaType contentType, String content);

    /**
     * Sets the content as UTF-8 for this message. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @FormatMethod
    HttpMessageSetters content(@FormatString String format, Object... content);

    /**
     * Sets the content for this message. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @FormatMethod
    HttpMessageSetters content(MediaType contentType, @FormatString String format,
                               Object... content);

    /**
     * Sets the content for this message. The {@code content} will be wrapped using
     * {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the request.
     */
    HttpMessageSetters content(MediaType contentType, byte[] content);

    /**
     * Sets the content for this message.
     */
    HttpMessageSetters content(MediaType contentType, HttpData content);

    /**
     * Sets the {@link Publisher} for this message.
     */
    HttpMessageSetters content(Publisher<? extends HttpData> content);

    /**
     * Sets the {@link Publisher} for this message.
     */
    HttpMessageSetters content(MediaType contentType, Publisher<? extends HttpData> content);

    /**
     * Sets the content for this message. The {@code content} is converted into JSON format
     * using the default {@link ObjectMapper}.
     */
    HttpMessageSetters contentJson(Object content);

    /**
     * Adds a header for this message.
     */
    HttpMessageSetters header(CharSequence name, Object value);

    /**
     * Adds multiple headers for this message.
     *
     * @see HttpHeaders
     */
    HttpMessageSetters headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers);

    /**
     * Adds an HTTP trailer for this message.
     */
    HttpMessageSetters trailer(CharSequence name, Object value);

    /**
     * Adds HTTP trailers for this message.
     */
    HttpMessageSetters trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers);

    /**
     * Sets a cookie for this message.
     *
     * @see Cookie
     */
    HttpMessageSetters cookie(Cookie cookie);

    /**
     * Sets multiple cookies for this message.
     *
     * @see Cookies
     */
    HttpMessageSetters cookies(Iterable<? extends Cookie> cookies);
}
