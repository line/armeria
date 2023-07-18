/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.testing.junit5.client;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Map;
import java.util.Map.Entry;

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.client.BlockingWebClientRequestPreparation;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.RequestPreparationSetters;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestMethodSetters;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

/**
 * Prepares and executes a new {@link HttpRequest} for {@link WebTestClient}.
 */
public final class WebTestClientRequestPreparation implements RequestPreparationSetters, RequestMethodSetters {

    private final BlockingWebClientRequestPreparation delegate;

    WebTestClientRequestPreparation(BlockingWebClientRequestPreparation delegate) {
        requireNonNull(delegate, "delegate");
        this.delegate = delegate;
    }

    /**
     * Builds and executes the request.
     */
    public TestHttpResponse execute() {
        return TestHttpResponse.of(delegate.execute());
    }

    @Override
    public WebTestClientRequestPreparation exchangeType(ExchangeType exchangeType) {
        delegate.exchangeType(exchangeType);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation requestOptions(RequestOptions requestOptions) {
        delegate.requestOptions(requestOptions);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation get(String path) {
        delegate.get(path);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation post(String path) {
        delegate.post(path);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation put(String path) {
        delegate.put(path);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation delete(String path) {
        delegate.delete(path);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation patch(String path) {
        delegate.patch(path);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation options(String path) {
        delegate.options(path);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation head(String path) {
        delegate.head(path);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation trace(String path) {
        delegate.trace(path);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation method(HttpMethod method) {
        delegate.method(method);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation path(String path) {
        delegate.path(path);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation content(String content) {
        delegate.content(content);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation content(MediaType contentType, CharSequence content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation content(MediaType contentType, String content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    @FormatMethod
    @SuppressWarnings("FormatStringAnnotation")
    public WebTestClientRequestPreparation content(@FormatString String format, Object... content) {
        delegate.content(format, content);
        return this;
    }

    @Override
    @SuppressWarnings("FormatStringAnnotation")
    public WebTestClientRequestPreparation content(MediaType contentType, @FormatString String format,
                                                   Object... content) {
        delegate.content(contentType, format, content);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation content(MediaType contentType, byte[] content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation content(MediaType contentType, HttpData content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation content(MediaType contentType,
                                                   Publisher<? extends HttpData> content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation contentJson(Object content) {
        delegate.contentJson(content);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation header(CharSequence name, Object value) {
        delegate.header(name, value);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        delegate.headers(headers);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation trailer(CharSequence name, Object value) {
        delegate.trailer(name, value);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        delegate.trailers(trailers);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation pathParam(String name, Object value) {
        delegate.pathParam(name, value);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation pathParams(Map<String, ?> pathParams) {
        delegate.pathParams(pathParams);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation disablePathParams() {
        delegate.disablePathParams();
        return this;
    }

    @Override
    public WebTestClientRequestPreparation queryParam(String name, Object value) {
        delegate.queryParam(name, value);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation queryParams(
            Iterable<? extends Entry<? extends String, String>> queryParams) {
        delegate.queryParams(queryParams);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation cookie(Cookie cookie) {
        delegate.cookie(cookie);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation cookies(Iterable<? extends Cookie> cookies) {
        delegate.cookies(cookies);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation responseTimeout(Duration responseTimeout) {
        delegate.responseTimeout(responseTimeout);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation responseTimeoutMillis(long responseTimeoutMillis) {
        delegate.responseTimeoutMillis(responseTimeoutMillis);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation writeTimeout(Duration writeTimeout) {
        delegate.writeTimeout(writeTimeout);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation writeTimeoutMillis(long writeTimeoutMillis) {
        delegate.writeTimeoutMillis(writeTimeoutMillis);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation maxResponseLength(long maxResponseLength) {
        delegate.maxResponseLength(maxResponseLength);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation requestAutoAbortDelay(Duration delay) {
        delegate.requestAutoAbortDelay(delay);
        return this;
    }

    @Override
    public WebTestClientRequestPreparation requestAutoAbortDelayMillis(long delayMillis) {
        delegate.requestAutoAbortDelayMillis(delayMillis);
        return this;
    }

    @Override
    public <V> WebTestClientRequestPreparation attr(AttributeKey<V> key, @Nullable V value) {
        delegate.attr(key, value);
        return this;
    }
}
