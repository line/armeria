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

import java.util.Locale;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;

abstract class AbstractHttpMessageBuilder {

    @Nullable
    private HttpData content;

    @Nullable
    private Publisher<? extends HttpData> publisher;

    @Nullable
    protected final HttpData content() {
        return content;
    }

    @Nullable
    protected final Publisher<? extends HttpData> publisher() {
        return publisher;
    }

    /**
     * Sets the content for this response.
     */
    public AbstractHttpMessageBuilder content(HttpData content) {
        requireNonNull(content, "content");
        this.content = content;
        return this;
    }

    /**
     * Sets the {@link Publisher} for this response.
     */
    public AbstractHttpMessageBuilder content(Publisher<? extends HttpData> content) {
        requireNonNull(content, "publisher");
        checkState(this.content == null, "content has been set already");
        publisher = content;
        return this;
    }

    /**
     * Sets the content as UTF_8 for this response.
     */
    public abstract AbstractHttpMessageBuilder content(String content);

    /**
     * Sets the content for this response.
     */
    public abstract AbstractHttpMessageBuilder content(MediaType contentType, CharSequence content);

    /**
     * Sets the content for this response.
     */
    public abstract AbstractHttpMessageBuilder content(MediaType contentType, String content);

    /**
     * Sets the content as UTF_8 for this response. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @FormatMethod
    public abstract AbstractHttpMessageBuilder content(@FormatString String format, Object... content);

    /**
     * Sets the content for this response. The {@code content} is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     */
    @FormatMethod
    public abstract AbstractHttpMessageBuilder content(MediaType contentType, @FormatString String format,
                                                       Object... content);

    /**
     * Sets the content for this response. The {@code content} will be wrapped using
     * {@link HttpData#wrap(byte[])}, so any changes made to {@code content} will be reflected in the response.
     */
    public abstract AbstractHttpMessageBuilder content(MediaType contentType, byte[] content);

    /**
     * Sets the content for this response.
     */
    public abstract AbstractHttpMessageBuilder content(MediaType contentType, HttpData content);

    /**
     * Sets the {@link Publisher} for this response.
     */
    public abstract AbstractHttpMessageBuilder content(MediaType contentType,
                                                       Publisher<? extends HttpData> content);

    /**
     * Sets the content for this response. The {@code content} that is converted into JSON
     * using the default {@link ObjectMapper}.
     */
    public AbstractHttpMessageBuilder contentJson(Object content) {
        try {
            this.content = HttpData.wrap(JacksonUtil.writeValueAsBytes(content));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.toString(), e);
        }
        return this;
    }
}
