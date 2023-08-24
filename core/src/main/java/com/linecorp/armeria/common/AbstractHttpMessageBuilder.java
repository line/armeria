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
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * A skeletal builder implementation for {@link HttpMessage}.
 */
@SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
@UnstableApi
public abstract class AbstractHttpMessageBuilder implements HttpMessageSetters {

    @Nullable
    private HttpData content;

    @Nullable
    private Publisher<? extends HttpData> publisher;

    @Nullable
    private HttpHeadersBuilder httpTrailers;

    /**
     * Creates a new instance.
     */
    protected AbstractHttpMessageBuilder() {}

    @Nullable
    final HttpData content() {
        return content;
    }

    /**
     * Returns the {@link Publisher} that was set by {@link #content(MediaType, Publisher)}.
     */
    @Nullable
    protected final Publisher<? extends HttpData> publisher() {
        return publisher;
    }

    @Nullable
    final HttpHeadersBuilder httpTrailers() {
        return httpTrailers;
    }

    abstract HttpHeadersBuilder headersBuilder();

    @Override
    public AbstractHttpMessageBuilder header(CharSequence name, Object value) {
        headersBuilder().addObject(requireNonNull(name, "name"),
                                   requireNonNull(value, "value"));
        return this;
    }

    @Override
    public AbstractHttpMessageBuilder headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        requireNonNull(headers, "headers");
        headersBuilder().add(headers);
        return this;
    }

    @Override
    public AbstractHttpMessageBuilder content(String content) {
        return content(MediaType.PLAIN_TEXT_UTF_8, content);
    }

    @Override
    public AbstractHttpMessageBuilder content(MediaType contentType, CharSequence content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        return content(contentType,
                       HttpData.of(contentType.charset(StandardCharsets.UTF_8),
                                   content));
    }

    @Override
    public AbstractHttpMessageBuilder content(MediaType contentType, String content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8),
                                                content));
    }

    @Override
    @FormatMethod
    public AbstractHttpMessageBuilder content(@FormatString String format, Object... content) {
        return content(MediaType.PLAIN_TEXT_UTF_8, format, content);
    }

    @Override
    @FormatMethod
    public AbstractHttpMessageBuilder content(MediaType contentType, @FormatString String format,
                                       Object... content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(format, "format");
        requireNonNull(content, "content");
        return content(contentType, HttpData.of(contentType.charset(StandardCharsets.UTF_8),
                                                format, content));
    }

    @Override
    public AbstractHttpMessageBuilder content(MediaType contentType, byte[] content) {
        requireNonNull(content, "content");
        return content(contentType, HttpData.wrap(content));
    }

    @Override
    public AbstractHttpMessageBuilder content(MediaType contentType, HttpData content) {
        requireNonNull(contentType, "contentType");
        requireNonNull(content, "content");
        checkState(publisher == null, "publisher has been set already");
        headersBuilder().contentType(contentType);
        this.content = content;
        return this;
    }

    @Override
    public AbstractHttpMessageBuilder content(Publisher<? extends HttpData> publisher) {
        requireNonNull(publisher, "publisher");
        checkState(content == null, "content has been set already");
        this.publisher = publisher;
        return this;
    }

    @Override
    public AbstractHttpMessageBuilder content(MediaType contentType, Publisher<? extends HttpData> publisher) {
        requireNonNull(contentType, "contentType");
        requireNonNull(publisher, "publisher");
        checkState(content == null, "content has been set already");
        headersBuilder().contentType(contentType);
        this.publisher = publisher;
        return this;
    }

    @Override
    public AbstractHttpMessageBuilder contentJson(Object content) {
        requireNonNull(content, "content");
        checkState(publisher == null, "publisher has been set already");
        try {
            return content(MediaType.JSON, HttpData.wrap(JacksonUtil.writeValueAsBytes(content)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize " + content, e);
        }
    }

    @Override
    public AbstractHttpMessageBuilder trailer(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        if (httpTrailers == null) {
            httpTrailers = HttpHeaders.builder();
        }
        httpTrailers.addObject(name, value);
        return this;
    }

    @Override
    public AbstractHttpMessageBuilder trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        requireNonNull(trailers, "trailers");
        if (httpTrailers == null) {
            httpTrailers = HttpHeaders.builder();
        }
        httpTrailers.add(trailers);
        return this;
    }
}
