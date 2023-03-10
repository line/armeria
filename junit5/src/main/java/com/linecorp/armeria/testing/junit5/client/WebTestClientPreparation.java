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
public final class WebTestClientPreparation implements RequestPreparationSetters, RequestMethodSetters {

    private final BlockingWebClientRequestPreparation delegate;

    WebTestClientPreparation(BlockingWebClientRequestPreparation delegate) {
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
    public WebTestClientPreparation exchangeType(ExchangeType exchangeType) {
        delegate.exchangeType(exchangeType);
        return this;
    }

    @Override
    public WebTestClientPreparation requestOptions(RequestOptions requestOptions) {
        delegate.requestOptions(requestOptions);
        return this;
    }

    @Override
    public WebTestClientPreparation get(String path) {
        delegate.get(path);
        return this;
    }

    @Override
    public WebTestClientPreparation post(String path) {
        delegate.post(path);
        return this;
    }

    @Override
    public WebTestClientPreparation put(String path) {
        delegate.put(path);
        return this;
    }

    @Override
    public WebTestClientPreparation delete(String path) {
        delegate.delete(path);
        return this;
    }

    @Override
    public WebTestClientPreparation patch(String path) {
        delegate.patch(path);
        return this;
    }

    @Override
    public WebTestClientPreparation options(String path) {
        delegate.options(path);
        return this;
    }

    @Override
    public WebTestClientPreparation head(String path) {
        delegate.head(path);
        return this;
    }

    @Override
    public WebTestClientPreparation trace(String path) {
        delegate.trace(path);
        return this;
    }

    @Override
    public WebTestClientPreparation method(HttpMethod method) {
        delegate.method(method);
        return this;
    }

    @Override
    public WebTestClientPreparation path(String path) {
        delegate.path(path);
        return this;
    }

    @Override
    public WebTestClientPreparation content(String content) {
        delegate.content(content);
        return this;
    }

    @Override
    public WebTestClientPreparation content(MediaType contentType, CharSequence content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public WebTestClientPreparation content(MediaType contentType, String content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    @FormatMethod
    @SuppressWarnings("FormatStringAnnotation")
    public WebTestClientPreparation content(@FormatString String format, Object... content) {
        delegate.content(format, content);
        return this;
    }

    @Override
    @SuppressWarnings("FormatStringAnnotation")
    public WebTestClientPreparation content(MediaType contentType, @FormatString String format,
                                            Object... content) {
        delegate.content(contentType, format, content);
        return this;
    }

    @Override
    public WebTestClientPreparation content(MediaType contentType, byte[] content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public WebTestClientPreparation content(MediaType contentType, HttpData content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public WebTestClientPreparation content(MediaType contentType, Publisher<? extends HttpData> content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public WebTestClientPreparation contentJson(Object content) {
        delegate.contentJson(content);
        return this;
    }

    @Override
    public WebTestClientPreparation header(CharSequence name, Object value) {
        delegate.header(name, value);
        return this;
    }

    @Override
    public WebTestClientPreparation headers(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        delegate.headers(headers);
        return this;
    }

    @Override
    public WebTestClientPreparation trailer(CharSequence name, Object value) {
        delegate.trailer(name, value);
        return this;
    }

    @Override
    public WebTestClientPreparation trailers(Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        delegate.trailers(trailers);
        return this;
    }

    @Override
    public WebTestClientPreparation pathParam(String name, Object value) {
        delegate.pathParam(name, value);
        return this;
    }

    @Override
    public WebTestClientPreparation pathParams(Map<String, ?> pathParams) {
        delegate.pathParams(pathParams);
        return this;
    }

    @Override
    public WebTestClientPreparation disablePathParams() {
        delegate.disablePathParams();
        return this;
    }

    @Override
    public WebTestClientPreparation queryParam(String name, Object value) {
        delegate.queryParam(name, value);
        return this;
    }

    @Override
    public WebTestClientPreparation queryParams(
            Iterable<? extends Entry<? extends String, String>> queryParams) {
        delegate.queryParams(queryParams);
        return this;
    }

    @Override
    public WebTestClientPreparation cookie(Cookie cookie) {
        delegate.cookie(cookie);
        return this;
    }

    @Override
    public WebTestClientPreparation cookies(Iterable<? extends Cookie> cookies) {
        delegate.cookies(cookies);
        return this;
    }

    @Override
    public WebTestClientPreparation responseTimeout(Duration responseTimeout) {
        delegate.responseTimeout(responseTimeout);
        return this;
    }

    @Override
    public WebTestClientPreparation responseTimeoutMillis(long responseTimeoutMillis) {
        delegate.responseTimeoutMillis(responseTimeoutMillis);
        return this;
    }

    @Override
    public WebTestClientPreparation writeTimeout(Duration writeTimeout) {
        delegate.writeTimeout(writeTimeout);
        return this;
    }

    @Override
    public WebTestClientPreparation writeTimeoutMillis(long writeTimeoutMillis) {
        delegate.writeTimeoutMillis(writeTimeoutMillis);
        return this;
    }

    @Override
    public WebTestClientPreparation maxResponseLength(long maxResponseLength) {
        delegate.maxResponseLength(maxResponseLength);
        return this;
    }

    @Override
    public <V> WebTestClientPreparation attr(AttributeKey<V> key, @Nullable V value) {
        delegate.attr(key, value);
        return this;
    }
}
