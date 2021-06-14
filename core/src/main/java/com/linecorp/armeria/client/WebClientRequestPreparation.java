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

package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.AbstractHttpRequestBuilder;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.AttributeKey;

/**
 * Prepares and executes a new {@link HttpRequest} for {@link WebClient}.
 */
public final class WebClientRequestPreparation extends AbstractHttpRequestBuilder {

    private final WebClient client;

    /**
     * Tells whether we need to use {@link Clients#withContextCustomizer(Consumer)} to set the request options.
     */
    private boolean needsContextCustomizer;

    // request options
    @Nullable
    private Map<AttributeKey<?>, Object> attributes;
    private long responseTimeoutMillis = -1;
    private long maxResponseLength = -1;

    WebClientRequestPreparation(WebClient client) {
        this.client = client;
    }

    /**
     * Builds and executes the request.
     */
    public HttpResponse execute() {
        final HttpRequest httpRequest = buildRequest();
        if (needsContextCustomizer) {
            try (SafeCloseable ignored = Clients.withContextCustomizer(ctx -> {
                if (responseTimeoutMillis >= 0) {
                    ctx.setResponseTimeoutMillis(responseTimeoutMillis);
                }

                if (maxResponseLength >= 0) {
                    ctx.setMaxResponseLength(maxResponseLength);
                }

                if (attributes != null && !attributes.isEmpty()) {
                    //noinspection unchecked
                    attributes.forEach((k, v) -> ctx.setAttr((AttributeKey<Object>) k, v));
                }
            })) {
                return client.execute(httpRequest);
            }
        } else {
            return client.execute(httpRequest);
        }
    }

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not fully received within
     * the specified {@link Duration} since the {@link Response} started or {@link Request} was fully sent.
     * {@link Duration#ZERO} disables the limit.
     */
    public WebClientRequestPreparation responseTimeout(Duration timeout) {
        responseTimeoutMillis(requireNonNull(timeout, "timeout").toMillis());
        return this;
    }

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not fully received within
     * the specified {@code responseTimeoutMillis} since the {@link Response} started or {@link Request} was
     * fully sent.
     * {@code 0} disables the limit.
     */
    public WebClientRequestPreparation responseTimeoutMillis(long responseTimeoutMillis) {
        checkArgument(responseTimeoutMillis >= 0, "responseTimeoutMillis: %s (expected: >= 0)",
                      responseTimeoutMillis);
        this.responseTimeoutMillis = responseTimeoutMillis;
        needsContextCustomizer = true;
        return this;
    }

    /**
     * Sets the maximum allowed length of a server response in bytes.
     *
     * @param maxResponseLength the maximum length in bytes. {@code 0} disables the limit.
     */
    public WebClientRequestPreparation maxResponseLength(long maxResponseLength) {
        checkArgument(maxResponseLength >= 0, "maxResponseLength: %s (expected: >= 0)", maxResponseLength);
        this.maxResponseLength = maxResponseLength;
        needsContextCustomizer = true;
        return this;
    }

    /**
     * Associates the specified value with the given {@link AttributeKey} in this request.
     * If this context previously contained a mapping for the {@link AttributeKey}, the old value is replaced
     * by the specified value.
     */
    public <V> WebClientRequestPreparation attr(AttributeKey<V> key, @Nullable V value) {
        requireNonNull(key, "key");

        if (attributes == null) {
            attributes = new HashMap<>();
            needsContextCustomizer = true;
        }

        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
        return this;
    }

    // Override the return types of the chaining methods in the superclass.

    @Override
    public WebClientRequestPreparation get(String path) {
        return (WebClientRequestPreparation) super.get(path);
    }

    @Override
    public WebClientRequestPreparation post(String path) {
        return (WebClientRequestPreparation) super.post(path);
    }

    @Override
    public WebClientRequestPreparation put(String path) {
        return (WebClientRequestPreparation) super.put(path);
    }

    @Override
    public WebClientRequestPreparation delete(String path) {
        return (WebClientRequestPreparation) super.delete(path);
    }

    @Override
    public WebClientRequestPreparation patch(String path) {
        return (WebClientRequestPreparation) super.patch(path);
    }

    @Override
    public WebClientRequestPreparation options(String path) {
        return (WebClientRequestPreparation) super.options(path);
    }

    @Override
    public WebClientRequestPreparation head(String path) {
        return (WebClientRequestPreparation) super.head(path);
    }

    @Override
    public WebClientRequestPreparation trace(String path) {
        return (WebClientRequestPreparation) super.trace(path);
    }

    @Override
    public WebClientRequestPreparation method(HttpMethod method) {
        return (WebClientRequestPreparation) super.method(method);
    }

    @Override
    public WebClientRequestPreparation path(String path) {
        return (WebClientRequestPreparation) super.path(path);
    }

    @Override
    public WebClientRequestPreparation content(MediaType contentType, CharSequence content) {
        return (WebClientRequestPreparation) super.content(contentType, content);
    }

    @Override
    public WebClientRequestPreparation content(MediaType contentType, String content) {
        return (WebClientRequestPreparation) super.content(contentType, content);
    }

    @Override
    @FormatMethod
    public WebClientRequestPreparation content(MediaType contentType, @FormatString String format,
                                               Object... content) {
        return (WebClientRequestPreparation) super.content(contentType, format, content);
    }

    @Override
    public WebClientRequestPreparation content(MediaType contentType, byte[] content) {
        return (WebClientRequestPreparation) super.content(contentType, content);
    }

    @Override
    public WebClientRequestPreparation content(MediaType contentType, HttpData content) {
        return (WebClientRequestPreparation) super.content(contentType, content);
    }

    @Override
    public WebClientRequestPreparation content(MediaType contentType, Publisher<? extends HttpData> publisher) {
        return (WebClientRequestPreparation) super.content(contentType, publisher);
    }

    @Override
    public WebClientRequestPreparation header(CharSequence name, Object value) {
        return (WebClientRequestPreparation) super.header(name, value);
    }

    @Override
    public WebClientRequestPreparation headers(
            Iterable<? extends Map.Entry<? extends CharSequence, String>> headers) {
        return (WebClientRequestPreparation) super.headers(headers);
    }

    @Override
    public WebClientRequestPreparation trailers(
            Iterable<? extends Map.Entry<? extends CharSequence, String>> trailers) {
        return (WebClientRequestPreparation) super.trailers(trailers);
    }

    @Override
    public WebClientRequestPreparation pathParam(String name, Object value) {
        return (WebClientRequestPreparation) super.pathParam(name, value);
    }

    @Override
    public WebClientRequestPreparation pathParams(Map<String, ?> pathParams) {
        return (WebClientRequestPreparation) super.pathParams(pathParams);
    }

    @Override
    public WebClientRequestPreparation disablePathParams() {
        return (WebClientRequestPreparation) super.disablePathParams();
    }

    @Override
    public WebClientRequestPreparation queryParam(String name, Object value) {
        return (WebClientRequestPreparation) super.queryParam(name, value);
    }

    @Override
    public WebClientRequestPreparation queryParams(
            Iterable<? extends Map.Entry<? extends String, String>> queryParams) {
        return (WebClientRequestPreparation) super.queryParams(queryParams);
    }

    @Override
    public WebClientRequestPreparation cookie(Cookie cookie) {
        return (WebClientRequestPreparation) super.cookie(cookie);
    }

    @Override
    public WebClientRequestPreparation cookies(Iterable<? extends Cookie> cookies) {
        return (WebClientRequestPreparation) super.cookies(cookies);
    }
}
