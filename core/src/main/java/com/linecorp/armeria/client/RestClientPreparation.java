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
import java.util.function.Predicate;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.FormatMethod;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.JacksonObjectMapperProvider;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Prepares and executes a new {@link HttpRequest} for {@link RestClient}.
 */
@UnstableApi
public final class RestClientPreparation implements RequestPreparationSetters {

    private final WebClientRequestPreparation delegate;

    RestClientPreparation(WebClient client, HttpMethod method, String path) {
        delegate = client.prepare();
        delegate.method(method);
        delegate.path(path);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the default
     * {@link ObjectMapper}.
     *
     * @see JacksonObjectMapperProvider
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(Class<? extends T> clazz) {
        requireNonNull(clazz, "clazz");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(clazz).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the default
     * {@link ObjectMapper}.<br>
     * {@link HttpStatus} type argument specify what type of response is allowed.
     *
     * @see JacksonObjectMapperProvider
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(Class<? extends T> clazz,
                                                            HttpStatus httpStatus) {
        requireNonNull(clazz, "clazz");
        requireNonNull(httpStatus, "httpStatus");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(clazz, httpStatus).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the default
     * {@link ObjectMapper}.
     * {@link HttpStatusClass} type argument specify what type of response is allowed.
     *
     * @see JacksonObjectMapperProvider
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(Class<? extends T> clazz,
                                                            HttpStatusClass httpStatusClass) {
        requireNonNull(clazz, "clazz");
        requireNonNull(httpStatusClass, "httpStatusClass");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(clazz, httpStatusClass).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the default
     * {@link ObjectMapper}.
     * {@link Predicate} type argument specify what type of response is allowed.
     *
     * @see JacksonObjectMapperProvider
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(Class<? extends T> clazz,
                                                            Predicate<? super HttpStatus> predicate) {
        requireNonNull(clazz, "clazz");
        requireNonNull(predicate, "predicate");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(clazz, predicate).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the specified
     * {@link ObjectMapper}.
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(Class<? extends T> clazz, ObjectMapper mapper) {
        requireNonNull(clazz, "clazz");
        requireNonNull(mapper, "mapper");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(clazz, mapper).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the specified
     * {@link ObjectMapper}.
     * {@link HttpStatus} type argument specify what type of response is allowed.
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(Class<? extends T> clazz, ObjectMapper mapper,
                                                            HttpStatus httpStatus) {
        requireNonNull(clazz, "clazz");
        requireNonNull(mapper, "mapper");
        requireNonNull(httpStatus, "httpStatus");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(clazz, mapper, httpStatus).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the specified
     * {@link ObjectMapper}.
     * {@link HttpStatusClass} type argument specify what type of response is allowed.
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(Class<? extends T> clazz, ObjectMapper mapper,
                                                            HttpStatusClass httpStatusClass) {
        requireNonNull(clazz, "clazz");
        requireNonNull(mapper, "mapper");
        requireNonNull(httpStatusClass, "httpStatusClass");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(clazz, mapper, httpStatusClass).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the specified
     * {@link ObjectMapper}.
     * {@link Predicate} type argument specify what type of response is allowed.
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(Class<? extends T> clazz, ObjectMapper mapper,
                                                            Predicate<? super HttpStatus> predicate) {
        requireNonNull(clazz, "clazz");
        requireNonNull(mapper, "mapper");
        requireNonNull(predicate, "predicate");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(clazz, mapper, predicate).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the default
     * {@link ObjectMapper}.
     *
     * @see JacksonObjectMapperProvider
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(TypeReference<? extends T> typeRef) {
        requireNonNull(typeRef, "typeRef");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(typeRef).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the default
     * {@link ObjectMapper}.
     * {@link HttpStatus} type argument specify what type of response is allowed.
     *
     * @see JacksonObjectMapperProvider
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(TypeReference<? extends T> typeRef,
                                                            HttpStatus httpStatus) {
        requireNonNull(typeRef, "typeRef");
        requireNonNull(httpStatus, "httpStatus");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(typeRef, httpStatus).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the default
     * {@link ObjectMapper}.
     * {@link HttpStatusClass} type argument specify what type of response is allowed.
     *
     * @see JacksonObjectMapperProvider
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(TypeReference<? extends T> typeRef,
                                                            HttpStatusClass httpStatusClass) {
        requireNonNull(typeRef, "typeRef");
        requireNonNull(httpStatusClass, "httpStatusClass");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(typeRef, httpStatusClass).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the default
     * {@link ObjectMapper}.
     * {@link Predicate} type argument specify what type of response is allowed.
     *
     * @see JacksonObjectMapperProvider
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(TypeReference<? extends T> typeRef,
                                                            Predicate<? super HttpStatus> predicate) {
        requireNonNull(typeRef, "typeRef");
        requireNonNull(predicate, "predicate");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(typeRef, predicate).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the specified
     * {@link ObjectMapper}.
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(TypeReference<? extends T> typeRef,
                                                            ObjectMapper mapper) {
        requireNonNull(typeRef, "typeRef");
        requireNonNull(mapper, "mapper");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(typeRef, mapper).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the specified
     * {@link ObjectMapper}.
     * {@link HttpStatus} type argument specify what type of response is allowed.
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(TypeReference<? extends T> typeRef,
                                                            ObjectMapper mapper,
                                                            HttpStatus httpStatus) {
        requireNonNull(typeRef, "typeRef");
        requireNonNull(mapper, "mapper");
        requireNonNull(httpStatus, "httpStatus");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(typeRef, mapper, httpStatus).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the specified
     * {@link ObjectMapper}.
     * {@link HttpStatusClass} type argument specify what type of response is allowed.
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(TypeReference<? extends T> typeRef,
                                                            ObjectMapper mapper,
                                                            HttpStatusClass httpStatusClass) {
        requireNonNull(typeRef, "typeRef");
        requireNonNull(mapper, "mapper");
        requireNonNull(httpStatusClass, "httpStatusClass");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(typeRef, mapper, httpStatusClass).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the JSON response body as the {@code T} object using the specified
     * {@link ObjectMapper}.
     * {@link Predicate} type argument specify what type of response is allowed.
     */
    public <T> CompletableFuture<ResponseEntity<T>> execute(TypeReference<? extends T> typeRef,
                                                            ObjectMapper mapper,
                                                            Predicate<? super HttpStatus> predicate) {
        requireNonNull(typeRef, "typeRef");
        requireNonNull(mapper, "mapper");
        requireNonNull(predicate, "predicate");
        final CompletableFuture<? extends ResponseEntity<? extends T>> response =
                delegate.asJson(typeRef, mapper, predicate).execute();
        return cast(response);
    }

    /**
     * Sends the HTTP request and converts the {@link HttpResponse} using the {@link ResponseAs}.
     */
    public <T> T execute(ResponseAs<HttpResponse, T> responseAs) {
        requireNonNull(responseAs, "responseAs");
        return delegate.as(responseAs).execute();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object any) {
        return (T) any;
    }

    @Override
    public RestClientPreparation pathParam(String name, Object value) {
        delegate.pathParam(name, value);
        return this;
    }

    @Override
    public RestClientPreparation pathParams(Map<String, ?> pathParams) {
        delegate.pathParams(pathParams);
        return this;
    }

    @Override
    public RestClientPreparation disablePathParams() {
        delegate.disablePathParams();
        return this;
    }

    @Override
    public RestClientPreparation queryParam(String name, Object value) {
        delegate.queryParam(name, value);
        return this;
    }

    @Override
    public RestClientPreparation queryParams(
            Iterable<? extends Entry<? extends String, String>> queryParams) {
        delegate.queryParams(queryParams);
        return this;
    }

    @Override
    public RestClientPreparation content(String content) {
        delegate.content(content);
        return this;
    }

    @Override
    public RestClientPreparation content(MediaType contentType, CharSequence content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public RestClientPreparation content(MediaType contentType, String content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    @FormatMethod
    public RestClientPreparation content(String format, Object... content) {
        delegate.content(format, content);
        return this;
    }

    @Override
    @FormatMethod
    public RestClientPreparation content(MediaType contentType, String format, Object... content) {
        delegate.content(contentType, format, content);
        return this;
    }

    @Override
    public RestClientPreparation content(MediaType contentType, byte[] content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public RestClientPreparation content(MediaType contentType, HttpData content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public RestClientPreparation content(MediaType contentType, Publisher<? extends HttpData> content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public RestClientPreparation contentJson(Object content) {
        delegate.contentJson(content);
        return this;
    }

    @Override
    public RestClientPreparation header(CharSequence name, Object value) {
        delegate.header(name, value);
        return this;
    }

    @Override
    public RestClientPreparation headers(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        delegate.headers(headers);
        return this;
    }

    @Override
    public RestClientPreparation trailer(CharSequence name, Object value) {
        delegate.trailer(name, value);
        return this;
    }

    @Override
    public RestClientPreparation trailers(Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        delegate.trailers(trailers);
        return this;
    }

    @Override
    public RestClientPreparation cookie(Cookie cookie) {
        delegate.cookie(cookie);
        return this;
    }

    @Override
    public RestClientPreparation cookies(Iterable<? extends Cookie> cookies) {
        delegate.cookies(cookies);
        return this;
    }

    @Override
    public RestClientPreparation responseTimeout(Duration responseTimeout) {
        delegate.responseTimeout(responseTimeout);
        return this;
    }

    @Override
    public RestClientPreparation responseTimeoutMillis(long responseTimeoutMillis) {
        delegate.responseTimeoutMillis(responseTimeoutMillis);
        return this;
    }

    @Override
    public RestClientPreparation writeTimeout(Duration writeTimeout) {
        delegate.writeTimeout(writeTimeout);
        return this;
    }

    @Override
    public RestClientPreparation writeTimeoutMillis(long writeTimeoutMillis) {
        delegate.writeTimeoutMillis(writeTimeoutMillis);
        return this;
    }

    @Override
    public RestClientPreparation maxResponseLength(long maxResponseLength) {
        delegate.maxResponseLength(maxResponseLength);
        return this;
    }

    @Override
    public <V> RestClientPreparation attr(AttributeKey<V> key, @Nullable V value) {
        delegate.attr(key, value);
        return this;
    }

    @Override
    public RestClientPreparation exchangeType(ExchangeType exchangeType) {
        delegate.exchangeType(exchangeType);
        return this;
    }

    @Override
    public RestClientPreparation requestOptions(RequestOptions requestOptions) {
        delegate.requestOptions(requestOptions);
        return this;
    }
}
