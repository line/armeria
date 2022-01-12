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

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.AbstractHttpRequestBuilder;
import com.linecorp.armeria.common.Cookie;
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
public class WebClientRequestPreparation extends AbstractHttpRequestBuilder
        implements RequestPreparationSetters<HttpResponse> {

    private final WebClient client;

    @Nullable
    private RequestOptionsBuilder requestOptionsBuilder;

    WebClientRequestPreparation(WebClient client) {
        this.client = client;
    }

    /**
     * Builds and executes the request.
     */
    @Override
    public HttpResponse execute() {
        final HttpRequest httpRequest = buildRequest();
        final RequestOptions requestOptions;
        if (requestOptionsBuilder != null) {
            requestOptions = requestOptionsBuilder.build();
        } else {
            requestOptions = RequestOptions.of();
        }
        return client.execute(httpRequest, requestOptions);
    }

    /**
     * Sets the specified {@link ResponseAs} that converts the {@link HttpResponse} into another.
     */
    @UnstableApi
    public <T> TransformingRequestPreparation<HttpResponse, T> as(ResponseAs<HttpResponse, T> responseAs) {
        return new TransformingRequestPreparation<>(this, responseAs);
    }

    /**
     * Sets the specified {@link ResponseAs} that converts the {@link HttpResponse} into an
     * {@link ResponseEntity}.
     */
    @UnstableApi
    public <T> FutureTransformingRequestPreparation<ResponseEntity<T>> asEntity(
            FutureResponseAs<ResponseEntity<T>> responseAs) {
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
     * Converts the content of the {@link HttpResponse} into a {@link Path}.
     * For example:
     * <pre>{@code
     * WebClient client = WebClient.of("https://api.example.com");
     * CompletableFuture<ResponseEntity<Path>> response =
     *     client.prepare()
     *           .get("/v1/items/1")
     *           .asPath(Paths.get("..."))
     *           .execute();
     * }</pre>
     */
    public FutureTransformingRequestPreparation<ResponseEntity<Path>> asPath(Path path) {
        return asEntity(ResponseAs.path(path));
    }

    /**
     * Converts the content of the {@link HttpResponse} into a {@link File}.
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
    public FutureTransformingRequestPreparation<ResponseEntity<Path>> asFile(File file) {
        requireNonNull(file, "file");
        return asEntity(ResponseAs.path(file.toPath()));
    }

    /**
     * Deserializes the content of the {@link HttpResponse} into the specified non-container type using the
     * default {@link ObjectMapper}.
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
     * {@link Collection} or {@link Map}.
     *
     * @throws InvalidHttpResponseException if the {@link HttpStatus} is of the response not
     *                                      {@linkplain HttpStatus#isSuccess() success} or fails to decode
     *                                      the response body into the result type.
     * @see JacksonObjectMapperProvider
     */
    @UnstableApi
    public <T> FutureTransformingRequestPreparation<ResponseEntity<T>> asJson(Class<? extends T> clazz) {
        return asEntity(ResponseAs.json(clazz));
    }

    /**
     * Deserializes the content of the {@link HttpResponse} into the specified Java type using the default
     * {@link ObjectMapper}.
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
     * @throws InvalidHttpResponseException if the {@link HttpStatus} is of the response not
     *                                      {@linkplain HttpStatus#isSuccess() success} or fails to decode
     *                                      the response body into the result type.
     * @see JacksonObjectMapperProvider
     */
    @UnstableApi
    public <T> FutureTransformingRequestPreparation<ResponseEntity<T>> asJson(TypeReference<? extends T> typeRef) {
        return asEntity(ResponseAs.json(typeRef));
    }

    /**
     * Sets the specified {@link RequestOptions} that could overwrite the previously configured values such as
     * {@link #responseTimeout(Duration)}, {@link #writeTimeout(Duration)}, {@link #maxResponseLength(long)}
     * and {@link #attr(AttributeKey, Object)}.
     */
    @Override
    public WebClientRequestPreparation requestOptions(RequestOptions requestOptions) {
        requireNonNull(requestOptions, "requestOptions");

        final long maxResponseLength = requestOptions.maxResponseLength();
        if (maxResponseLength >= 0) {
            maxResponseLength(maxResponseLength);
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

        return this;
    }

    @Override
    public WebClientRequestPreparation responseTimeout(Duration responseTimeout) {
        return responseTimeoutMillis(requireNonNull(responseTimeout, "responseTimeout").toMillis());
    }

    @Override
    public WebClientRequestPreparation responseTimeoutMillis(long responseTimeoutMillis) {
        if (requestOptionsBuilder == null) {
            requestOptionsBuilder = RequestOptions.builder();
        }
        requestOptionsBuilder.responseTimeoutMillis(responseTimeoutMillis);
        return this;
    }

    @Override
    public WebClientRequestPreparation writeTimeout(Duration writeTimeout) {
        return writeTimeoutMillis(requireNonNull(writeTimeout, "writeTimeout").toMillis());
    }

    @Override
    public WebClientRequestPreparation writeTimeoutMillis(long writeTimeoutMillis) {
        if (requestOptionsBuilder == null) {
            requestOptionsBuilder = RequestOptions.builder();
        }
        requestOptionsBuilder.writeTimeoutMillis(writeTimeoutMillis);
        return this;
    }

    @Override
    public WebClientRequestPreparation maxResponseLength(long maxResponseLength) {
        if (requestOptionsBuilder == null) {
            requestOptionsBuilder = RequestOptions.builder();
        }
        requestOptionsBuilder.maxResponseLength(maxResponseLength);
        return this;
    }

    @Override
    public <V> WebClientRequestPreparation attr(AttributeKey<V> key, @Nullable V value) {
        if (requestOptionsBuilder == null) {
            requestOptionsBuilder = RequestOptions.builder();
        }
        requestOptionsBuilder.attr(key, value);
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
    public WebClientRequestPreparation content(@FormatString String format, Object... content) {
        return (WebClientRequestPreparation) super.content(format, content);
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
