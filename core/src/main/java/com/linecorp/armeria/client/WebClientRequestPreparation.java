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

import static com.linecorp.armeria.client.DefaultWebClient.RESPONSE_STREAMING_REQUEST_OPTIONS;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.AbstractHttpRequestBuilder;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.JacksonObjectMapperProvider;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Prepares and executes a new {@link HttpRequest} for {@link WebClient}.
 */
public final class WebClientRequestPreparation
        extends AbstractHttpRequestBuilder implements WebRequestPreparationSetters<HttpResponse> {

    private final WebClient client;

    @Nullable
    private RequestOptionsBuilder requestOptionsBuilder;

    WebClientRequestPreparation(WebClient client) {
        this.client = client;
    }

    @Override
    public HttpResponse execute() {
        final HttpRequest httpRequest = buildRequest();
        final RequestOptions requestOptions = buildRequestOptions();
        return client.execute(httpRequest, requestOptions);
    }

    private RequestOptions buildRequestOptions() {
        final RequestOptions requestOptions;
        final boolean requestStreaming = isRequestStreaming();
        if (requestOptionsBuilder != null) {
            if (requestOptionsBuilder.exchangeType() == null) {
                if (!requestStreaming) {
                    requestOptionsBuilder.exchangeType(ExchangeType.RESPONSE_STREAMING);
                }
            }
            requestOptions = requestOptionsBuilder.build();
        } else {
            if (!requestStreaming) {
                requestOptions = RESPONSE_STREAMING_REQUEST_OPTIONS;
            } else {
                requestOptions = RequestOptions.of();
            }
        }
        return requestOptions;
    }

    boolean isRequestStreaming() {
        //noinspection ReactiveStreamsUnusedPublisher
        return publisher() != null;
    }

    /**
     * Sets the specified {@link ResponseAs} that converts the {@link HttpResponse} into another.
     */
    @UnstableApi
    public <T> TransformingRequestPreparation<HttpResponse, T> as(ResponseAs<HttpResponse, T> responseAs) {
        requireNonNull(responseAs, "responseAs");
        return new TransformingRequestPreparation<>(this, responseAs);
    }

    /**
     * Sets the specified {@link ResponseAs} that converts the {@link HttpResponse} into
     * a {@link ResponseEntity}.
     */
    @UnstableApi
    public <T> FutureTransformingRequestPreparation<ResponseEntity<T>> asEntity(
            FutureResponseAs<ResponseEntity<T>> responseAs) {
        requireNonNull(responseAs, "responseAs");
        return new FutureTransformingRequestPreparation<>(this, responseAs);
    }

    /**
     * Converts the content of the {@link HttpResponse} into bytes.
     * For example:
     * <pre>{@code
     * WebClient client = WebClient.of("https://api.example.com");
     * CompletableFuture<ResponseEntity<byte[]>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asBytes()
     *           .execute();
     * }</pre>
     */
    @UnstableApi
    public FutureTransformingRequestPreparation<ResponseEntity<byte[]>> asBytes() {
        return asEntity(ResponseAs.bytes());
    }

    /**
     * Converts the content of the {@link HttpResponse} into a {@link String}.
     * For example:
     * <pre>{@code
     * WebClient client = WebClient.of("https://api.example.com");
     * CompletableFuture<ResponseEntity<String>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asString()
     *           .execute();
     * }</pre>
     */
    @UnstableApi
    public FutureTransformingRequestPreparation<ResponseEntity<String>> asString() {
        return asEntity(ResponseAs.string());
    }

    /**
     * Writes the content of the {@link HttpResponse} to the {@link Path}.
     * For example:
     * <pre>{@code
     * WebClient client = WebClient.of("https://api.example.com");
     * CompletableFuture<ResponseEntity<Path>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asFile(Paths.get("..."))
     *           .execute();
     * }</pre>
     */
    @UnstableApi
    public FutureTransformingRequestPreparation<ResponseEntity<Path>> asFile(Path path) {
        requireNonNull(path, "path");
        return asEntity(ResponseAs.path(path));
    }

    /**
     * Writes the content of the {@link HttpResponse} to the {@link File}.
     * For example:
     * <pre>{@code
     * WebClient client = WebClient.of("https://api.example.com");
     * CompletableFuture<ResponseEntity<File>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asFile(new File("..."))
     *           .execute();
     * }</pre>
     */
    @UnstableApi
    public FutureTransformingRequestPreparation<ResponseEntity<Path>> asFile(File file) {
        requireNonNull(file, "file");
        return asFile(file.toPath());
    }

    /**
     * Deserializes the JSON content of the {@link HttpResponse} into the specified non-container type using
     * the default {@link ObjectMapper}.
     * For example:
     * <pre>{@code
     * WebClient client = WebClient.of("https://api.example.com");
     * CompletableFuture<ResponseEntity<MyObject>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asJson(MyObject.class)
     *           .execute();
     * }</pre>
     *
     * <p>Note that this method should NOT be used if the result type is a container such as
     * {@link Collection} or {@link Map}. Use {@link #asJson(TypeReference)} for the container type.
     *
     * @throws InvalidHttpResponseException if the {@link HttpStatus} of the response is not
     *                                      {@linkplain HttpStatus#isSuccess() success} or fails to decode
     *                                      the response body into the result type.
     * @see JacksonObjectMapperProvider
     */
    @UnstableApi
    public <T> FutureTransformingRequestPreparation<ResponseEntity<T>> asJson(Class<? extends T> clazz) {
        requireNonNull(clazz, "clazz");
        return asEntity(ResponseAs.json(clazz));
    }

    /**
     * Deserializes the JSON content of the {@link HttpResponse} into the specified non-container type using
     * the specified {@link ObjectMapper}.
     * For example:
     * <pre>{@code
     * ObjectMapper mapper = ...;
     * WebClient client = WebClient.of("https://api.example.com");
     * CompletableFuture<ResponseEntity<MyObject>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asJson(MyObject.class, mapper)
     *           .execute();
     * }</pre>
     *
     * <p>Note that this method should NOT be used if the result type is a container such as
     * {@link Collection} or {@link Map}. Use {@link #asJson(TypeReference, ObjectMapper)} for the container
     * type.
     *
     * @throws InvalidHttpResponseException if the {@link HttpStatus} of the response is not
     *                                      {@linkplain HttpStatus#isSuccess() success} or fails to decode
     *                                      the response body into the result type.
     */
    @UnstableApi
    public <T> FutureTransformingRequestPreparation<ResponseEntity<T>> asJson(Class<? extends T> clazz,
                                                                              ObjectMapper mapper) {
        requireNonNull(clazz, "clazz");
        requireNonNull(mapper, "mapper");
        return asEntity(ResponseAs.json(clazz, mapper));
    }

    /**
     * Deserializes the JSON content of the {@link HttpResponse} into the specified Java type using the default
     * {@link ObjectMapper}. This method is useful when you want to deserialize the content into a container
     * type such as {@link List} and {@link Map}.
     * For example:
     * <pre>{@code
     * WebClient client = WebClient.of("https://api.example.com");
     * CompletableFuture<ResponseEntity<List<MyObject>>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asJson(new TypeReference<List<MyObject>> {})
     *           .execute();
     * }</pre>
     *
     * @throws InvalidHttpResponseException if the {@link HttpStatus} of the response is not
     *                                      {@linkplain HttpStatus#isSuccess() success} or fails to decode
     *                                      the response body into the result type.
     * @see JacksonObjectMapperProvider
     */
    @UnstableApi
    public <T> FutureTransformingRequestPreparation<ResponseEntity<T>> asJson(
            TypeReference<? extends T> typeRef) {
        requireNonNull(typeRef, "typeRef");
        return asEntity(ResponseAs.json(typeRef));
    }

    /**
     * Deserializes the JSON content of the {@link HttpResponse} into the specified Java type using the
     * specified {@link ObjectMapper}. This method is useful when you want to deserialize the content into a
     * container type such as {@link List} and {@link Map}.
     * For example:
     * <pre>{@code
     * ObjectMapper mapper = ...;
     * WebClient client = WebClient.of("https://api.example.com");
     * CompletableFuture<ResponseEntity<List<MyObject>>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asJson(new TypeReference<List<MyObject>> {}, mapper)
     *           .execute();
     * }</pre>
     *
     * @throws InvalidHttpResponseException if the {@link HttpStatus} of the response is not
     *                                      {@linkplain HttpStatus#isSuccess() success} or fails to decode
     *                                      the response body into the result type.
     */
    @UnstableApi
    public <T> FutureTransformingRequestPreparation<ResponseEntity<T>> asJson(
            TypeReference<? extends T> typeRef, ObjectMapper mapper) {
        requireNonNull(typeRef, "typeRef");
        requireNonNull(mapper, "mapper");
        return asEntity(ResponseAs.json(typeRef, mapper));
    }

    @Override
    public WebClientRequestPreparation requestOptions(RequestOptions requestOptions) {
        requireNonNull(requestOptions, "requestOptions");

        final long maxResponseLength = requestOptions.maxResponseLength();
        if (maxResponseLength >= 0) {
            maxResponseLength(maxResponseLength);
        }

        final Long delayMillis = requestOptions.requestAutoAbortDelayMillis();
        if (delayMillis != null) {
            requestAutoAbortDelayMillis(delayMillis);
        }

        final long responseTimeoutMillis = requestOptions.responseTimeoutMillis();
        if (responseTimeoutMillis >= 0) {
            responseTimeoutMillis(responseTimeoutMillis);
        }

        final long writeTimeoutMillis = requestOptions.writeTimeoutMillis();
        if (writeTimeoutMillis >= 0) {
            writeTimeoutMillis(writeTimeoutMillis);
        }

        final Map<AttributeKey<?>, Object> attrs = requestOptions.attrs();
        if (!attrs.isEmpty()) {
            //noinspection unchecked
            attrs.forEach((key, value) -> attr((AttributeKey<Object>) key, value));
        }
        final ExchangeType exchangeType = requestOptions.exchangeType();
        if (exchangeType != null) {
            exchangeType(exchangeType);
        }
        return this;
    }

    @Override
    public WebClientRequestPreparation responseTimeout(Duration responseTimeout) {
        return responseTimeoutMillis(requireNonNull(responseTimeout, "responseTimeout").toMillis());
    }

    @Override
    public WebClientRequestPreparation responseTimeoutMillis(long responseTimeoutMillis) {
        requestOptionsBuilder().responseTimeoutMillis(responseTimeoutMillis);
        return this;
    }

    @Override
    public WebClientRequestPreparation writeTimeout(Duration writeTimeout) {
        return writeTimeoutMillis(requireNonNull(writeTimeout, "writeTimeout").toMillis());
    }

    @Override
    public WebClientRequestPreparation writeTimeoutMillis(long writeTimeoutMillis) {
        requestOptionsBuilder().writeTimeoutMillis(writeTimeoutMillis);
        return this;
    }

    @Override
    public WebClientRequestPreparation maxResponseLength(long maxResponseLength) {
        requestOptionsBuilder().maxResponseLength(maxResponseLength);
        return this;
    }

    @Override
    public WebClientRequestPreparation requestAutoAbortDelay(Duration delay) {
        requestOptionsBuilder().requestAutoAbortDelay(delay);
        return this;
    }

    @Override
    public WebClientRequestPreparation requestAutoAbortDelayMillis(long delayMillis) {
        requestOptionsBuilder().requestAutoAbortDelayMillis(delayMillis);
        return this;
    }

    @Override
    public <V> WebClientRequestPreparation attr(AttributeKey<V> key, @Nullable V value) {
        requestOptionsBuilder().attr(key, value);
        return this;
    }

    @Nullable
    ExchangeType exchangeType() {
        if (requestOptionsBuilder == null) {
            return null;
        }
        return requestOptionsBuilder.exchangeType();
    }

    @Override
    public WebClientRequestPreparation exchangeType(ExchangeType exchangeType) {
        requestOptionsBuilder().exchangeType(exchangeType);
        return this;
    }

    private RequestOptionsBuilder requestOptionsBuilder() {
        if (requestOptionsBuilder == null) {
            requestOptionsBuilder = RequestOptions.builder();
        }
        return requestOptionsBuilder;
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
    public WebClientRequestPreparation content(String content) {
        return (WebClientRequestPreparation) super.content(content);
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
    @SuppressWarnings("FormatStringAnnotation")
    public WebClientRequestPreparation content(@FormatString String format, Object... content) {
        return (WebClientRequestPreparation) super.content(format, content);
    }

    @Override
    @FormatMethod
    @SuppressWarnings("FormatStringAnnotation")
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
    public WebClientRequestPreparation content(Publisher<? extends HttpData> publisher) {
        return (WebClientRequestPreparation) super.content(publisher);
    }

    @Override
    public WebClientRequestPreparation content(MediaType contentType, Publisher<? extends HttpData> publisher) {
        return (WebClientRequestPreparation) super.content(contentType, publisher);
    }

    @Override
    public WebClientRequestPreparation contentJson(Object content) {
        return (WebClientRequestPreparation) super.contentJson(content);
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
