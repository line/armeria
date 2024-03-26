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

package com.linecorp.armeria.client.websocket;

import static com.linecorp.armeria.internal.client.ClientUtil.UNDEFINED_URI;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Unwrappable;

/**
 * A WebSocket client.
 * This client has a few different default values for {@link ClientOptions} from {@link WebClient}
 * because of the nature of WebSocket. See {@link WebSocketClientBuilder} for more information.
 *
 * <p>WebSocket client example:
 * <pre>{@code
 * WebSocketClient client = WebSocketClient.of("ws://www.example.com");
 * client.connect("/chat").thenAccept(webSocketSession -> {
 *     // Write messages to the server.
 *     WebSocketWriter writer = WebSocket.streaming();
 *     webSocketSessions.setOutbound(writer);
 *     outbound.write("Hello ");
 *     // You can also use backpressure using whenConsumed().
 *     outbound.whenConsumed().thenRun(() -> outbound.write("world!"));
 *
 *     // Read messages from the server.
 *     Subscriber<WebSocketFrame> myWebSocketSubscriber = new Subscriber<WebSocketFrame>() {
 *         @Override
 *         public void onSubscribe(Subscription s) {
 *             s.request(Long.MAX_VALUE);
 *         }
 *         @Override
 *         public void onNext(WebSocketFrame webSocketFrame) {
 *             if (webSocketFrame.type() == WebSocketFrameType.TEXT) {
 *                 System.out.println(webSocketFrame.text());
 *             }
 *             ...
 *         }
 *         ...
 *     };
 *     webSocketSessions.inbound().subscribe(myWebSocketSubscriber);
 * });
 * }</pre>
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455">The WebSocket Protocol</a>
 */
@UnstableApi
public interface WebSocketClient extends ClientBuilderParams, Unwrappable {

    /**
     * Returns a {@link WebSocketClient} without a base URI.
     */
    static WebSocketClient of() {
        return DefaultWebSocketClient.DEFAULT;
    }

    /**
     * Returns a new {@link WebSocketClient} that connects to the specified {@code uri} using the
     * default options.
     */
    static WebSocketClient of(String uri) {
        return builder(uri).build();
    }

    /**
     * Returns a new {@link WebSocketClient} that connects to the specified {@link URI} using the
     * default options.
     */
    static WebSocketClient of(URI uri) {
        return builder(uri).build();
    }

    /**
     * Returns a new {@link WebSocketClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@code scheme} using the default {@link ClientOptions}.
     */
    static WebSocketClient of(String scheme, EndpointGroup endpointGroup) {
        return builder(scheme, endpointGroup).build();
    }

    /**
     * Returns a new {@link WebSocketClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@link Scheme} using the default {@link ClientOptions}.
     */
    static WebSocketClient of(Scheme scheme, EndpointGroup endpointGroup) {
        return builder(scheme, endpointGroup).build();
    }

    /**
     * Returns a new {@link WebSocketClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} using the default {@link ClientOptions}.
     */
    static WebSocketClient of(SessionProtocol protocol, EndpointGroup endpointGroup) {
        return builder(protocol, endpointGroup).build();
    }

    /**
     * Returns a new {@link WebSocketClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@code scheme} and {@code path} using the default {@link ClientOptions}.
     */
    static WebSocketClient of(String scheme, EndpointGroup endpointGroup, String path) {
        return builder(scheme, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link WebSocketClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@link Scheme} and {@code path} using the default {@link ClientOptions}.
     */
    static WebSocketClient of(Scheme scheme, EndpointGroup endpointGroup, String path) {
        return builder(scheme, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link WebSocketClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@code scheme} and {@code path} using the default {@link ClientOptions}.
     */
    static WebSocketClient of(SessionProtocol protocol, EndpointGroup endpointGroup, String path) {
        return builder(protocol, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link WebSocketClientBuilder} without a base URI.
     */
    static WebSocketClientBuilder builder() {
        return builder(UNDEFINED_URI);
    }

    /**
     * Returns a new {@link WebSocketClientBuilder} created with the specified base {@code uri}.
     */
    static WebSocketClientBuilder builder(String uri) {
        return builder(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Returns a new {@link WebSocketClientBuilder} created with the specified base {@link URI}.
     */
    static WebSocketClientBuilder builder(URI uri) {
        return new WebSocketClientBuilder(requireNonNull(uri, "uri"));
    }

    /**
     * Returns a new {@link WebSocketClientBuilder} created with the specified {@code scheme}
     * and the {@link EndpointGroup}.
     */
    static WebSocketClientBuilder builder(String scheme, EndpointGroup endpointGroup) {
        requireNonNull(scheme, "scheme");
        return builder(Scheme.parse(scheme), endpointGroup);
    }

    /**
     * Returns a new {@link WebSocketClientBuilder} created with the specified {@link Scheme}
     * and the {@link EndpointGroup}.
     */
    static WebSocketClientBuilder builder(Scheme scheme, EndpointGroup endpointGroup) {
        requireNonNull(scheme, "scheme");
        requireNonNull(endpointGroup, "endpointGroup");
        return new WebSocketClientBuilder(scheme, endpointGroup, null);
    }

    /**
     * Returns a new {@link WebSocketClientBuilder} created with the specified {@link SessionProtocol}
     * and the {@link EndpointGroup}.
     */
    static WebSocketClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup) {
        requireNonNull(protocol, "protocol");
        return builder(Scheme.of(SerializationFormat.WS, protocol), endpointGroup);
    }

    /**
     * Returns a new {@link WebSocketClientBuilder} created with the specified {@code scheme},
     * the {@link EndpointGroup}, and the {@code path}.
     */
    static WebSocketClientBuilder builder(String scheme, EndpointGroup endpointGroup, String path) {
        requireNonNull(scheme, "scheme");
        return builder(Scheme.parse(scheme), endpointGroup, path);
    }

    /**
     * Returns a new {@link WebSocketClientBuilder} created with the specified {@link Scheme},
     * the {@link EndpointGroup}, and the {@code path}.
     */
    static WebSocketClientBuilder builder(Scheme scheme, EndpointGroup endpointGroup, String path) {
        requireNonNull(scheme, "scheme");
        requireNonNull(endpointGroup, "endpointGroup");
        return new WebSocketClientBuilder(scheme, endpointGroup, path);
    }

    /**
     * Returns a new {@link WebSocketClientBuilder} created with the specified {@link SessionProtocol},
     * the {@link EndpointGroup}, and the {@code path}.
     */
    static WebSocketClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup, String path) {
        requireNonNull(protocol, "protocol");
        return builder(Scheme.of(SerializationFormat.WS, protocol), endpointGroup, path);
    }

    /**
     * Connects to the specified {@code path}.
     *
     * <p>Note that the returned {@link CompletableFuture} is exceptionally completes with
     * {@link WebSocketClientHandshakeException} if the handshake failed.
     */
    default CompletableFuture<WebSocketSession> connect(String path) {
        return connect(path, HttpHeaders.of());
    }

    /**
     * Connects to the specified {@code path} with the specified headers.
     *
     * <p>Note that the returned {@link CompletableFuture} is exceptionally completes with
     * {@link WebSocketClientHandshakeException} if the handshake failed.
     */
    default CompletableFuture<WebSocketSession> connect(String path, HttpHeaders headers) {
        return connect(path, headers, RequestOptions.of());
    }

    /**
     * Connects to the specified {@code path} with the specified {@link HttpHeaders} and {@link RequestOptions}.
     *
     * <p>Note that the returned {@link CompletableFuture} is exceptionally completes with
     * {@link WebSocketClientHandshakeException} if the handshake failed.
     */
    CompletableFuture<WebSocketSession> connect(String path, HttpHeaders headers, RequestOptions options);

    @Override
    WebClient unwrap();
}
