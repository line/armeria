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

import static com.linecorp.armeria.client.ResponseAsUtil.OBJECT_MAPPER;
import static com.linecorp.armeria.client.ResponseAsUtil.SUCCESS_PREDICATE;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.JacksonObjectMapperProvider;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Prepares and executes a new {@link HttpRequest} for {@link BlockingWebClient}.
 */
@UnstableApi
public final class BlockingWebClientRequestPreparation
        implements WebRequestPreparationSetters<AggregatedHttpResponse> {

    private final WebClientRequestPreparation delegate;

    BlockingWebClientRequestPreparation(WebClientRequestPreparation delegate) {
        this.delegate = delegate;
        delegate.exchangeType(ExchangeType.UNARY);
    }

    /**
     * Builds and executes the blocking request.
     */
    @Override
    public AggregatedHttpResponse execute() {
        return ResponseAs.blocking().as(delegate.execute());
    }

    /**
     * Sets the specified {@link ResponseAs} that converts the {@link AggregatedHttpResponse} into another.
     */
    @UnstableApi
    public <U> TransformingRequestPreparation<AggregatedHttpResponse, U> as(
            ResponseAs<AggregatedHttpResponse, U> responseAs) {
        requireNonNull(responseAs, "responseAs");
        return new TransformingRequestPreparation<>(this, responseAs);
    }

    /**
     * Converts the response content into bytes.
     * For example:
     * <pre>{@code
     * BlockingWebClient client = BlockingWebClient.of("https://api.example.com");
     * ResponseEntity<byte[]> response = client.prepare()
     *                                         .get("/v1/items/1")
     *                                         .asBytes()
     *                                         .execute();
     * }</pre>
     */
    @UnstableApi
    public TransformingRequestPreparation<AggregatedHttpResponse, ResponseEntity<byte[]>> asBytes() {
        return as(AggregatedResponseAs.bytes());
    }

    /**
     * Converts the response content into {@link String}.
     * For example:
     * <pre>{@code
     * BlockingWebClient client = BlockingWebClient.of("https://api.example.com");
     * ResponseEntity<String> response = client.prepare()
     *                                         .get("/v1/items/1")
     *                                         .asString()
     *                                         .execute();
     * }</pre>
     */
    @UnstableApi
    public TransformingRequestPreparation<AggregatedHttpResponse, ResponseEntity<String>> asString() {
        return as(AggregatedResponseAs.string());
    }

    /**
     * Deserializes the JSON response content into the specified non-container type
     * using the default {@link ObjectMapper}.
     * For example:
     * <pre>{@code
     * BlockingWebClient client = BlockingWebClient.of("https://api.example.com");
     * ResponseEntity<MyObject> response = client.prepare()
     *                                           .get("/v1/items/1")
     *                                           .asJson(MyObject.class)
     *                                           .execute();
     * }</pre>
     *
     * <p>Note that this method should NOT be used if the result type is a container such as {@link Collection}
     * or {@link Map}. Use {@link #asJson(TypeReference)} for the container type.
     *
     * @throws InvalidHttpResponseException if the {@link HttpStatus} of the response is not
     *                                      {@linkplain HttpStatus#isSuccess() success} or fails to decode
     *                                      the response body into the result type.
     * @see JacksonObjectMapperProvider
     */
    @UnstableApi
    public <T> TransformingRequestPreparation<AggregatedHttpResponse, ResponseEntity<T>> asJson(
            Class<? extends T> clazz) {
        return asJson(clazz, OBJECT_MAPPER);
    }

    /**
     * Deserializes the JSON response content into the specified non-container type
     * using the specified {@link ObjectMapper}.
     * For example:
     * <pre>{@code
     * ObjectMapper mapper = ...;
     * BlockingWebClient client = BlockingWebClient.of("https://api.example.com");
     * ResponseEntity<MyObject> response = client.prepare()
     *                                           .get("/v1/items/1")
     *                                           .asJson(MyObject.class, mapper)
     *                                           .execute();
     * }</pre>
     *
     * <p>Note that this method should NOT be used if the result type is a container such as {@link Collection}
     * or {@link Map}. Use {@link #asJson(TypeReference, ObjectMapper)} for the container type.
     *
     * @throws InvalidHttpResponseException if the {@link HttpStatus} of the response is not
     *                                      {@linkplain HttpStatus#isSuccess() success} or fails to decode
     *                                      the response body into the result type.
     */
    @UnstableApi
    public <T> TransformingRequestPreparation<AggregatedHttpResponse, ResponseEntity<T>> asJson(
            Class<? extends T> clazz, ObjectMapper mapper) {
        requireNonNull(clazz, "clazz");
        requireNonNull(mapper, "mapper");
        return as(AggregatedResponseAs.json(clazz, mapper, SUCCESS_PREDICATE));
    }

    /**
     * Deserializes the JSON response content into the specified Java type using
     * the default {@link ObjectMapper}. This method is useful when you want to deserialize
     * the content into a container type such as {@link List} and {@link Map}.
     * For example:
     * <pre>{@code
     * BlockingWebClient client = BlockingWebClient.of("https://api.example.com");
     * ResponseEntity<List<MyObject>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asJson(new TypeReference<List<MyObject>>() {})
     *           .execute();
     * }</pre>
     *
     * @throws InvalidHttpResponseException if the {@link HttpStatus} of the response is not
     *                                      {@linkplain HttpStatus#isSuccess() success} or fails to decode
     *                                      the response body into the result type.
     * @see JacksonObjectMapperProvider
     */
    @UnstableApi
    public <T> TransformingRequestPreparation<AggregatedHttpResponse, ResponseEntity<T>> asJson(
            TypeReference<? extends T> typeRef) {
        return asJson(typeRef, OBJECT_MAPPER);
    }

    /**
     * Deserializes the JSON response content into the specified Java type using
     * the specified {@link ObjectMapper}.
     * For example:
     * <pre>{@code
     * ObjectMapper mapper = ...;
     * BlockingWebClient client = BlockingWebClient.of("https://api.example.com");
     * ResponseEntity<List<MyObject>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asJson(new TypeReference<List<MyObject>>() {}, mapper)
     *           .execute();
     * }</pre>
     *
     * @throws InvalidHttpResponseException if the {@link HttpStatus} of the response is not
     *                                      {@linkplain HttpStatus#isSuccess() success} or fails to decode
     *                                      the response body into the result type.
     */
    @UnstableApi
    public <T> TransformingRequestPreparation<AggregatedHttpResponse, ResponseEntity<T>> asJson(
            TypeReference<? extends T> typeRef, ObjectMapper mapper) {
        requireNonNull(typeRef, "typeRef");
        requireNonNull(mapper, "mapper");
        return as(AggregatedResponseAs.json(typeRef, mapper, SUCCESS_PREDICATE));
    }

    @Override
    public BlockingWebClientRequestPreparation exchangeType(ExchangeType exchangeType) {
        delegate.exchangeType(exchangeType);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation requestOptions(RequestOptions requestOptions) {
        delegate.requestOptions(requestOptions);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation get(String path) {
        delegate.get(path);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation post(String path) {
        delegate.post(path);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation put(String path) {
        delegate.put(path);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation delete(String path) {
        delegate.delete(path);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation patch(String path) {
        delegate.patch(path);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation options(String path) {
        delegate.options(path);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation head(String path) {
        delegate.head(path);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation trace(String path) {
        delegate.trace(path);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation method(HttpMethod method) {
        delegate.method(method);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation path(String path) {
        delegate.path(path);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation content(String content) {
        delegate.content(content);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation content(MediaType contentType, CharSequence content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation content(MediaType contentType, String content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    @FormatMethod
    @SuppressWarnings("FormatStringAnnotation")
    public BlockingWebClientRequestPreparation content(@FormatString String format, Object... content) {
        delegate.content(format, content);
        return this;
    }

    @Override
    @FormatMethod
    @SuppressWarnings("FormatStringAnnotation")
    public BlockingWebClientRequestPreparation content(MediaType contentType, @FormatString String format,
                                                       Object... content) {
        delegate.content(contentType, format, content);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation content(MediaType contentType, byte[] content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation content(MediaType contentType, HttpData content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation content(Publisher<? extends HttpData> content) {
        delegate.content(content);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation content(MediaType contentType,
                                                       Publisher<? extends HttpData> content) {
        delegate.content(contentType, content);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation contentJson(Object content) {
        delegate.contentJson(content);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation header(CharSequence name, Object value) {
        delegate.header(name, value);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation headers(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        delegate.headers(headers);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation trailer(CharSequence name, Object value) {
        delegate.trailer(name, value);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation trailers(
            Iterable<? extends Entry<? extends CharSequence, String>> trailers) {
        delegate.trailers(trailers);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation pathParam(String name, Object value) {
        delegate.pathParam(name, value);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation pathParams(Map<String, ?> pathParams) {
        delegate.pathParams(pathParams);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation disablePathParams() {
        delegate.disablePathParams();
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation queryParam(String name, Object value) {
        delegate.queryParam(name, value);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation queryParams(
            Iterable<? extends Entry<? extends String, String>> queryParams) {
        delegate.queryParams(queryParams);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation cookie(Cookie cookie) {
        delegate.cookie(cookie);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation cookies(Iterable<? extends Cookie> cookies) {
        delegate.cookies(cookies);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation responseTimeout(Duration responseTimeout) {
        delegate.responseTimeout(responseTimeout);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation responseTimeoutMillis(long responseTimeoutMillis) {
        delegate.responseTimeoutMillis(responseTimeoutMillis);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation writeTimeout(Duration writeTimeout) {
        delegate.writeTimeout(writeTimeout);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation writeTimeoutMillis(long writeTimeoutMillis) {
        delegate.writeTimeoutMillis(writeTimeoutMillis);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation maxResponseLength(long maxResponseLength) {
        delegate.maxResponseLength(maxResponseLength);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation requestAutoAbortDelay(Duration delay) {
        delegate.requestAutoAbortDelay(delay);
        return this;
    }

    @Override
    public BlockingWebClientRequestPreparation requestAutoAbortDelayMillis(long delayMillis) {
        delegate.requestAutoAbortDelayMillis(delayMillis);
        return this;
    }

    @Override
    public <V> BlockingWebClientRequestPreparation attr(AttributeKey<V> key, @Nullable V value) {
        delegate.attr(key, value);
        return this;
    }
}
