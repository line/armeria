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
import com.linecorp.armeria.common.HttpMessageSetters;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestMethodSetters;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Prepares and executes a new {@link HttpRequest} for {@link TestBlockingWebClient}.
 */
@UnstableApi
public final class TestBlockingWebClientRequestPreparation
        implements RequestPreparationSetters, RequestMethodSetters {

    private final BlockingWebClientRequestPreparation delegate;

    TestBlockingWebClientRequestPreparation(BlockingWebClientRequestPreparation delegate) {
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
    public TestBlockingWebClientRequestPreparation exchangeType(ExchangeType exchangeType) {
        delegate.exchangeType(exchangeType);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation requestOptions(RequestOptions requestOptions) {
        delegate.requestOptions(requestOptions);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation get(String path) {
        delegate.get(path);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation post(String path) {
        delegate.post(path);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation put(String path) {
        delegate.put(path);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation delete(String path) {
        delegate.delete(path);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation patch(String path) {
        delegate.patch(path);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation options(String path) {
        delegate.options(path);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation head(String path) {
        delegate.head(path);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation trace(String path) {
        delegate.trace(path);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation method(HttpMethod method) {
        delegate.method(method);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation path(String path) {
        delegate.path(path);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation content(String content) {
        delegate.content(content);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation content(MediaType contentType, CharSequence content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation content(MediaType contentType, String content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    @FormatMethod
    @SuppressWarnings("FormatStringAnnotation")
    public TestBlockingWebClientRequestPreparation content(@FormatString String format, Object... content) {
        delegate.content(format, content);
        return this;
    }

    @Override
    @SuppressWarnings("FormatStringAnnotation")
    public TestBlockingWebClientRequestPreparation content(MediaType contentType, @FormatString String format,
                                                           Object... content) {
        delegate.content(contentType, format, content);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation content(MediaType contentType, byte[] content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation content(MediaType contentType, HttpData content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public HttpMessageSetters content(Publisher<? extends HttpData> content) {
        delegate.content(content);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation content(MediaType contentType,
                                                           Publisher<? extends HttpData> content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation contentJson(Object content) {
        delegate.contentJson(content);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation header(CharSequence name, Object value) {
        delegate.header(name, value);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        delegate.headers(headers);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation trailer(CharSequence name, Object value) {
        delegate.trailer(name, value);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        delegate.trailers(trailers);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation pathParam(String name, Object value) {
        delegate.pathParam(name, value);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation pathParams(Map<String, ?> pathParams) {
        delegate.pathParams(pathParams);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation disablePathParams() {
        delegate.disablePathParams();
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation queryParam(String name, Object value) {
        delegate.queryParam(name, value);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation queryParams(
            Iterable<? extends Entry<? extends String, String>> queryParams) {
        delegate.queryParams(queryParams);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation cookie(Cookie cookie) {
        delegate.cookie(cookie);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation cookies(Iterable<? extends Cookie> cookies) {
        delegate.cookies(cookies);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation responseTimeout(Duration responseTimeout) {
        delegate.responseTimeout(responseTimeout);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation responseTimeoutMillis(long responseTimeoutMillis) {
        delegate.responseTimeoutMillis(responseTimeoutMillis);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation writeTimeout(Duration writeTimeout) {
        delegate.writeTimeout(writeTimeout);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation writeTimeoutMillis(long writeTimeoutMillis) {
        delegate.writeTimeoutMillis(writeTimeoutMillis);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation maxResponseLength(long maxResponseLength) {
        delegate.maxResponseLength(maxResponseLength);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation requestAutoAbortDelay(Duration delay) {
        delegate.requestAutoAbortDelay(delay);
        return this;
    }

    @Override
    public TestBlockingWebClientRequestPreparation requestAutoAbortDelayMillis(long delayMillis) {
        delegate.requestAutoAbortDelayMillis(delayMillis);
        return this;
    }

    @Override
    public <V> TestBlockingWebClientRequestPreparation attr(AttributeKey<V> key, @Nullable V value) {
        delegate.attr(key, value);
        return this;
    }
}
