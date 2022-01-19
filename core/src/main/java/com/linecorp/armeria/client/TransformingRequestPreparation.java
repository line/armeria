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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Map;
import java.util.Map.Entry;

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.FormatMethod;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Prepares and executes a new {@link HttpRequest} for a {@link WebClient} or {@link BlockingWebClient}, and
 * transforms a {@link HttpResponse} into the {@code T} type object.
 */
@UnstableApi
public class TransformingRequestPreparation<T, R> implements RequestPreparationSetters<R> {

    private final RequestPreparationSetters<T> delegate;
    private ResponseAs<T, R> responseAs;

    TransformingRequestPreparation(RequestPreparationSetters<T> delegate, ResponseAs<T, R> responseAs) {
        this.delegate = delegate;
        this.responseAs = responseAs;
    }

    /**
     * Builds and executes the request.
     */
    @Override
    public R execute() {
        // TODO(ikhoon): Use ResponseAs.requiresAggregation() to specify a proper ExchangeType
        //               to RequestOptions.
        return responseAs.as(delegate.execute());
    }

    /**
     * Sets the specified {@link RequestOptions} that could overwrite the previously configured values such as
     * {@link #responseTimeout(Duration)}, {@link #writeTimeout(Duration)}, {@link #maxResponseLength(long)}
     * and {@link #attr(AttributeKey, Object)}.
     */
    @Override
    public TransformingRequestPreparation<T, R> requestOptions(RequestOptions requestOptions) {
        delegate.requestOptions(requestOptions);
        return this;
    }

    /**
     * Sets the specified {@link ResponseAs} that converts the {@code T} type object into another.
     */
    @SuppressWarnings("unchecked")
    public <U> TransformingRequestPreparation<T, U> as(ResponseAs<R, U> responseAs) {
        requireNonNull(responseAs, "responseAs");
        this.responseAs = (ResponseAs<T, R>) this.responseAs.andThen(responseAs);
        return (TransformingRequestPreparation<T, U>) this;
    }

    @Override
    public TransformingRequestPreparation<T, R> get(String path) {
        delegate.get(path);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> post(String path) {
        delegate.post(path);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> put(String path) {
        delegate.put(path);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> delete(String path) {
        delegate.delete(path);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> patch(String path) {
        delegate.patch(path);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> options(String path) {
        delegate.options(path);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> head(String path) {
        delegate.head(path);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> trace(String path) {
        delegate.trace(path);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> method(HttpMethod method) {
        delegate.method(method);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> path(String path) {
        delegate.path(path);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> content(String content) {
        delegate.content(content);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> content(MediaType contentType,
                                                        CharSequence content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> content(MediaType contentType, String content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    @FormatMethod
    public TransformingRequestPreparation<T, R> content(String format, Object... content) {
        delegate.content(format, content);
        return this;
    }

    @Override
    @FormatMethod
    public TransformingRequestPreparation<T, R> content(MediaType contentType, String format,
                                                        Object... content) {
        delegate.content(contentType, format, content);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> content(MediaType contentType, byte[] content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> content(MediaType contentType,
                                                        HttpData content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> content(MediaType contentType,
                                                        Publisher<? extends HttpData> content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> contentJson(Object content) {
        delegate.contentJson(content);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> header(CharSequence name, Object value) {
        delegate.header(name, value);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        delegate.headers(headers);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        delegate.trailers(trailers);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> pathParam(String name, Object value) {
        delegate.pathParam(name, value);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> pathParams(Map<String, ?> pathParams) {
        delegate.pathParams(pathParams);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> disablePathParams() {
        delegate.disablePathParams();
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> queryParam(String name, Object value) {
        delegate.queryParam(name, value);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> queryParams(
            Iterable<? extends Entry<? extends String, String>> queryParams) {
        delegate.queryParams(queryParams);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> cookie(Cookie cookie) {
        delegate.cookie(cookie);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> cookies(Iterable<? extends Cookie> cookies) {
        delegate.cookies(cookies);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> responseTimeout(Duration responseTimeout) {
        delegate.responseTimeout(responseTimeout);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> responseTimeoutMillis(long responseTimeoutMillis) {
        delegate.responseTimeoutMillis(responseTimeoutMillis);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> writeTimeout(Duration writeTimeout) {
        delegate.writeTimeout(writeTimeout);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> writeTimeoutMillis(long writeTimeoutMillis) {
        delegate.writeTimeoutMillis(writeTimeoutMillis);
        return this;
    }

    @Override
    public TransformingRequestPreparation<T, R> maxResponseLength(long maxResponseLength) {
        delegate.maxResponseLength(maxResponseLength);
        return this;
    }

    @Override
    public <V> TransformingRequestPreparation<T, R> attr(AttributeKey<V> key, @Nullable V value) {
        delegate.attr(key, value);
        return this;
    }
}
