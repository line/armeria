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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Map.Entry;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;

@SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
abstract class AbstractHttpMessageBuilder {

    private final HttpHeadersBuilder httpHeaders = HttpHeaders.builder();

    @Nullable
    private HttpData content;

    @Nullable
    private Publisher<? extends HttpData> publisher;

    @Nullable
    private HttpHeadersBuilder httpTrailers;

    protected final HttpHeadersBuilder httpHeaders() {
        return httpHeaders;
    }

    @Nullable
    protected final HttpData content() {
        return content;
    }

    @Nullable
    protected final Publisher<? extends HttpData> publisher() {
        return publisher;
    }

    @Nullable
    protected final HttpHeadersBuilder httpTrailers() {
        return httpTrailers;
    }

    public AbstractHttpMessageBuilder header(CharSequence name, Object value) {
        httpHeaders.addObject(requireNonNull(name, "name"),
                              requireNonNull(value, "value"));
        return this;
    }

    public AbstractHttpMessageBuilder headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        requireNonNull(headers, "headers");
        httpHeaders.add(headers);
        return this;
    }

    protected AbstractHttpMessageBuilder content(String content) {
        return content(MediaType.PLAIN_TEXT_UTF_8, content);
    }

    protected AbstractHttpMessageBuilder content(MediaType contentType, CharSequence content) {
        requireNonNull(contentType, "contentType");
        return content(contentType,
                       HttpData.of(contentType.charset(StandardCharsets.UTF_8),
                                   content));
    }

    protected AbstractHttpMessageBuilder content(MediaType contentType, String content) {
        requireNonNull(contentType, "contentType");
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8),
                                                content));
    }

    @FormatMethod
    protected AbstractHttpMessageBuilder content(@FormatString String format, Object... content) {
        return content(MediaType.PLAIN_TEXT_UTF_8, format, content);
    }

    @FormatMethod
    protected AbstractHttpMessageBuilder content(MediaType contentType, @FormatString String format,
                                                 Object... content) {
        requireNonNull(contentType, "contentType");
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8),
                                                format, content));
    }

    protected AbstractHttpMessageBuilder content(MediaType contentType, byte[] content) {
        return content(contentType, HttpData.wrap(content));
    }

    protected AbstractHttpMessageBuilder content(MediaType contentType, HttpData content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        checkState(publisher == null, "publisher has been set already");
        httpHeaders.contentType(contentType);
        this.content = content;
        return this;
    }

    protected AbstractHttpMessageBuilder content(MediaType contentType, Publisher<? extends HttpData> content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "publisher");
        checkState(this.content == null, "content has been set already");
        httpHeaders.contentType(contentType);
        publisher = content;
        return this;
    }

    protected AbstractHttpMessageBuilder contentJson(Object content) {
        requireNonNull(content, "content");
        httpHeaders.contentType(MediaType.JSON);
        try {
            this.content = HttpData.wrap(JacksonUtil.writeValueAsBytes(content));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.toString(), e);
        }
        return this;
    }

    protected AbstractHttpMessageBuilder trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        requireNonNull(trailers, "trailers");
        if (httpTrailers == null) {
            httpTrailers = HttpHeaders.builder();
        }
        httpTrailers.set(trailers);
        return this;
    }
}
