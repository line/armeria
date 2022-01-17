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
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.reactivestreams.Publisher;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.util.AttributeKey;

/**
 * Prepares and executes a new {@link HttpRequest} for {@link WebClient}, and asynchronously transforms a
 * {@link HttpResponse} into a {@code T} type object.
 */
@UnstableApi
public final class FutureTransformingRequestPreparation<T>
        implements RequestPreparationSetters<CompletableFuture<T>> {

    private final WebClientRequestPreparation delegate;
    private FutureResponseAs<T> responseAs;
    // The actual type of Object is either ResponseEntity<T> or Throwable.
    @Nullable
    private Function<? super Throwable, ?> errorHandler;

    FutureTransformingRequestPreparation(WebClientRequestPreparation delegate,
                                         FutureResponseAs<T> responseAs) {
        this.delegate = delegate;
        this.responseAs = responseAs;
    }

    @Override
    public CompletableFuture<T> execute() {
        CompletableFuture<T> response;
        try {
            response = responseAs.as(delegate.execute());
        } catch (Exception ex) {
            response = new CompletableFuture<>();
            response.completeExceptionally(ex);
        }

        if (errorHandler == null) {
            return response;
        }

        return response.exceptionally(cause -> {
            cause = Exceptions.peel(cause);
            final Object maybeRecovered = errorHandler.apply(cause);
            if (maybeRecovered instanceof Throwable) {
                // The cause was translated.
                return Exceptions.throwUnsafely((Throwable) maybeRecovered);
            }
            if (maybeRecovered != null) {
                //noinspection unchecked
                return (T) maybeRecovered;
            }
            // Not handled.
            return Exceptions.throwUnsafely(cause);
        });
    }

    /**
     * Sets a {@link ResponseAs} that converts the {@code T} type object into another.
     */
    @UnstableApi
    @SuppressWarnings("unchecked")
    public <U> FutureTransformingRequestPreparation<U> as(
            ResponseAs<? super T, ? extends U> responseAs) {
        this.responseAs = (FutureResponseAs<T>) this.responseAs.map(responseAs::as);
        return (FutureTransformingRequestPreparation<U>) this;
    }

    /**
     * Recovers a failed {@link HttpResponse} by switching to a returned fallback object returned by the
     * {@code function}. If a {@code null} value is returned, the cause will not be recovered.
     */
    @UnstableApi
    public FutureTransformingRequestPreparation<T> recover(
            Function<? super Throwable, ? extends @Nullable T> function) {
        requireNonNull(function, "function");
        errorHandler(function);
        return this;
    }

    /**
     * Transforms a {@link Throwable} raised while receiving the response by applying the specified
     * {@link Function}. If a {@code null} value is returned, the original cause will be used as is.
     */
    @UnstableApi
    public FutureTransformingRequestPreparation<T> mapError(
            Function<? super Throwable, ? extends @Nullable Throwable> function) {
        requireNonNull(function, "function");
        errorHandler(function);
        return this;
    }

    private void errorHandler(Function<? super Throwable, ?> errorHandler) {
        if (this.errorHandler == null) {
            this.errorHandler = errorHandler;
        } else {
            this.errorHandler = this.errorHandler.andThen(obj -> {
                if (obj instanceof Throwable) {
                    try {
                        final Object result = errorHandler.apply((Throwable) obj);
                        if (result != null) {
                            return result;
                        }

                        // Not handled.
                        return obj;
                    } catch (Throwable ex) {
                        // Pass the new Throwable to the next chain.
                        return ex;
                    }
                } else {
                    // A cause was recovered already.
                    assert obj instanceof ResponseEntity;
                    return obj;
                }
            });
        }
    }

    @Override
    public FutureTransformingRequestPreparation<T> requestOptions(RequestOptions requestOptions) {
        delegate.requestOptions(requestOptions);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> get(String path) {
        delegate.get(path);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> post(String path) {
        delegate.post(path);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> put(String path) {
        delegate.put(path);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> delete(String path) {
        delegate.delete(path);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> patch(String path) {
        delegate.patch(path);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> options(String path) {
        delegate.options(path);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> head(String path) {
        delegate.head(path);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> trace(String path) {
        delegate.trace(path);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> method(HttpMethod method) {
        delegate.method(method);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> path(String path) {
        delegate.path(path);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> content(String content) {
        delegate.content(content);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> content(MediaType contentType, CharSequence content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> content(MediaType contentType, String content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    @FormatMethod
    public FutureTransformingRequestPreparation<T> content(@FormatString String format, Object... content) {
        delegate.content(format, content);
        return this;
    }

    @Override
    @FormatMethod
    public FutureTransformingRequestPreparation<T> content(MediaType contentType, @FormatString String format,
                                                           Object... content) {
        delegate.content(contentType, format, content);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> content(MediaType contentType, byte[] content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> content(MediaType contentType, HttpData content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> content(MediaType contentType,
                                                           Publisher<? extends HttpData> content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> contentJson(Object content) {
        delegate.contentJson(content);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> header(CharSequence name, Object value) {
        delegate.header(name, value);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        delegate.headers(headers);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        delegate.trailers(trailers);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> pathParam(String name, Object value) {
        delegate.pathParam(name, value);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> pathParams(Map<String, ?> pathParams) {
        delegate.pathParams(pathParams);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> disablePathParams() {
        delegate.disablePathParams();
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> queryParam(String name, Object value) {
        delegate.queryParam(name, value);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> queryParams(
            Iterable<? extends Entry<? extends String, String>> queryParams) {
        delegate.queryParams(queryParams);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> cookie(Cookie cookie) {
        delegate.cookie(cookie);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> cookies(Iterable<? extends Cookie> cookies) {
        delegate.cookies(cookies);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> responseTimeout(Duration responseTimeout) {
        delegate.responseTimeout(responseTimeout);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> responseTimeoutMillis(long responseTimeoutMillis) {
        delegate.responseTimeoutMillis(responseTimeoutMillis);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> writeTimeout(Duration writeTimeout) {
        delegate.writeTimeout(writeTimeout);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> writeTimeoutMillis(long writeTimeoutMillis) {
        delegate.writeTimeoutMillis(writeTimeoutMillis);
        return this;
    }

    @Override
    public FutureTransformingRequestPreparation<T> maxResponseLength(long maxResponseLength) {
        delegate.maxResponseLength(maxResponseLength);
        return this;
    }

    @Override
    public <V> FutureTransformingRequestPreparation<T> attr(AttributeKey<V> key, @Nullable V value) {
        delegate.attr(key, value);
        return this;
    }
}
