/*
 * Copyright 2021 LINE Corporation
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

import java.time.Duration;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Provides the setters for building {@link RequestOptions}.
 */
@UnstableApi
public interface RequestOptionsSetters {

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not fully received within
     * the specified {@link Duration} since the {@link Response} started or {@link Request} was fully sent.
     * {@link Duration#ZERO} disables the limit.
     */
    RequestOptionsSetters responseTimeout(Duration responseTimeout);

    /**
     * Schedules the response timeout that is triggered when the {@link Response} is not fully received within
     * the specified {@code responseTimeoutMillis} since the {@link Response} started or {@link Request} was
     * fully sent. {@code 0} disables the limit.
     */
    RequestOptionsSetters responseTimeoutMillis(long responseTimeoutMillis);

    /**
     * Sets the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. {@link Duration#ZERO} disables the limit.
     */
    RequestOptionsSetters writeTimeout(Duration writeTimeout);

    /**
     * Sets the amount of time allowed until the initial write attempt of the current {@link Request}
     * succeeds. {@code 0} disables the limit.
     */
    RequestOptionsSetters writeTimeoutMillis(long writeTimeoutMillis);

    /**
     * Sets the maximum allowed length of a server response in bytes.
     * {@code 0} disables the limit.
     */
    RequestOptionsSetters maxResponseLength(long maxResponseLength);

    /**
     * Sets the amount of time to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * This may be useful when you want to send additional data even after the response is complete.
     * Specify {@link Duration#ZERO} to abort the {@link HttpRequest} immediately. Any negative value will not
     * abort the request automatically. There is no delay by default.
     */
    RequestOptionsSetters requestAutoAbortDelay(Duration delay);

    /**
     * Sets the amount of time in millis to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * This may be useful when you want to send additional data even after the response is complete.
     * Specify {@code 0} to abort the {@link HttpRequest} immediately. Any negative value will not
     * abort the request automatically. There is no delay by default.
     */
    RequestOptionsSetters requestAutoAbortDelayMillis(long delayMillis);

    /**
     * Associates the specified value with the given {@link AttributeKey} in this request.
     * If this context previously contained a mapping for the {@link AttributeKey}, the old value is replaced
     * by the specified value.
     */
    <V> RequestOptionsSetters attr(AttributeKey<V> key, @Nullable V value);

    /**
     * Sets the {@link ExchangeType} that determines whether to stream an {@link HttpRequest} or
     * {@link HttpResponse}. Note that an {@link HttpRequest} will be aggregated before being written if
     * {@link ExchangeType#UNARY} or {@link ExchangeType#RESPONSE_STREAMING} is set. If unspecified,
     * the {@link Client}s try to infer a proper {@link ExchangeType}
     * depending on the content type of a request and a response. Here are examples:
     *
     * <p>{@link WebClient}
     *
     * <pre>{@code
     * WebClient client = WebClient.of("https://armeria.dev");
     *
     * try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
     *     client.prepare()
     *           .post("/api/v1/items")
     *           .contentJson(new Item(...)) // A non-streaming request type.
     *           .asString()                 // A non-streaming response type.
     *           .execute();
     *     assert captor.get().exchangeType() == ExchangeType.UNARY;
     * }
     *
     * try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
     *     client.get("/api/v1/items")   // A non-streaming request type.
     *           .aggregate();           // A return type is not specified; Assuming that response streaming
     *                                   // is enabled.
     *     assert captor.get().exchangeType() == ExchangeType.RESPONSE_STREAMING;
     * }
     *
     * try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
     *     client.prepare()
     *           .post("/api/v1/items")
     *           .content(MediaType.JSON_LINES, StreamMessage.of(...)) // A streaming request type.
     *           .asFile(Path.get("/path/to/destination"))             // A streaming response type.
     *           .execute();
     *     assert captor.get().exchangeType() == ExchangeType.BIDI_STREAMING;
     * }
     * }</pre>
     *
     * <p>{@link BlockingWebClient}
     *
     * <p>Since a request and a response of {@link BlockingWebClient} are fully aggregated,
     * {@link ExchangeType#UNARY} is only supported.
     * <pre>{@code
     * try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
     *     AggregatedHttpResponse response = client.blocking().get("/api/v1/items");
     *     assert captor.get().exchangeType() == ExchangeType.UNARY;
     * }
     * }</pre>
     *
     * <p>gRPC clients
     *
     * <p>An {@link ExchangeType} is automatically inferred from the
     * {@code io.grpc.MethodDescriptor.MethodType}.
     * <pre>{@code
     * try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
     *     Response response = grpcClient.unaryCall(...);
     *     assert captor.get().exchangeType() == ExchangeType.UNARY;
     * }
     * }</pre>
     *
     * <p>Thrift clients
     *
     * <p>Thrift protocols do not support streaming. {@link ExchangeType#UNARY} is only supported.
     */
    @UnstableApi
    RequestOptionsSetters exchangeType(ExchangeType exchangeType);
}
