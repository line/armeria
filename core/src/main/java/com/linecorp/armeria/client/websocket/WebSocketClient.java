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

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Unwrappable;

/**
 * A WebSocket client.
 */
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
     */
    default void connect(String path, WebSocketClientHandler handler) {
        connect(path, ImmutableList.of(), handler);
    }

    /**
     * Connects to the specified {@code path} with the subprotocol.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-1.9">
     *     Subprotocols Using the WebSocket Protocol</a>
     */
    default void connect(String path, String subprotocol, WebSocketClientHandler handler) {
        requireNonNull(subprotocol, "subprotocol");
        connect(path, ImmutableList.of(subprotocol), handler);
    }

    /**
     * Connects to the specified {@code path} with the subprotocols.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-1.9">
     *     Subprotocols Using the WebSocket Protocol</a>
     */
    void connect(String path, Iterable<String> subprotocols, WebSocketClientHandler handler);

    /**
     * Connects to the endpoint with the {@link RequestHeaders}.
     */
    void connect(RequestHeaders requestHeaders, WebSocketClientHandler handler);

    @Override
    WebClient unwrap();

    CompletableFuture<WebSocketSession> connect(String path);
}
