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
package com.linecorp.armeria.server.websocket;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.websocket.WebSocketDecoderConfig;
import com.linecorp.armeria.common.websocket.WebSocketFrame;

/**
 * Builds a {@link WebSocketService}.
 */
@UnstableApi
public final class WebSocketServiceBuilder {

    private static final WebSocketDecoderConfig defaultDecoderConfig = WebSocketDecoderConfig.builder().build();

    private final WebSocketHandler handler;
    private WebSocketDecoderConfig decoderConfig = defaultDecoderConfig;
    private Set<String> subprotocols = ImmutableSet.of();
    private Set<String> allowedOrigins = ImmutableSet.of();
    private long closeTimeoutMillis = Flags.defaultRequestTimeoutMillis();

    WebSocketServiceBuilder(WebSocketHandler handler) {
        this.handler = requireNonNull(handler, "handler");
    }

    /**
     * Sets the {@link WebSocketDecoderConfig} to decode {@link WebSocketFrame}s.
     */
    public WebSocketServiceBuilder decoderConfig(WebSocketDecoderConfig decoderConfig) {
        this.decoderConfig = requireNonNull(decoderConfig, "decoderConfig");
        return this;
    }

    /**
     * Sets the subprotocols to use with the WebSocket Protocol.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-1.9">
     *     Subprotocols Using the WebSocket Protocol</a>
     */
    public WebSocketServiceBuilder subprotocols(String... subprotocols) {
        return subprotocols(ImmutableSet.copyOf(requireNonNull(subprotocols, "subprotocols")));
    }

    /**
     * Sets the subprotocols to use with the WebSocket Protocol.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-1.9">
     *     Subprotocols Using the WebSocket Protocol</a>
     */
    public WebSocketServiceBuilder subprotocols(Iterable<String> subprotocols) {
        this.subprotocols = ImmutableSet.copyOf(requireNonNull(subprotocols, "subprotocols"));
        return this;
    }

    /**
     * Sets the allowed origins.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigins(String... allowedOrigins) {
        return allowedOrigins(ImmutableSet.copyOf(requireNonNull(allowedOrigins, "allowedOrigins")));
    }

    /**
     * Sets the allowed origins.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigins(Iterable<String> allowedOrigins) {
        this.allowedOrigins = ImmutableSet.copyOf(requireNonNull(allowedOrigins, "allowedOrigins"));
        return this;
    }

    /**
     * Sets the timeout that waits a close frame from the peer after sending a close frame to the peer.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-1.4">Closing Handshake</a>
     */
    public WebSocketServiceBuilder closeTimeout(Duration timeout) {
        return closeTimeoutMillis(requireNonNull(timeout, "timeout").toMillis());
    }

    /**
     * Sets the timeout that waits a close frame from the peer after sending a close frame to the peer
     * in milliseconds. Specify {@code 0} to close the connection as soon as sending a close frame.
     * {@link Flags#defaultRequestTimeoutMillis()} is used by default.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-1.4">Closing Handshake</a>
     */
    public WebSocketServiceBuilder closeTimeoutMillis(long closeTimeoutMillis) {
        checkArgument(closeTimeoutMillis >= 0, "closeTimeoutMillis: %s (expected >= 0)", closeTimeoutMillis);
        this.closeTimeoutMillis = closeTimeoutMillis;
        return this;
    }

    /**
     * Returns a newly-created {@link WebSocketService} with the properties set so far.
     */
    public WebSocketService build() {
        return new WebSocketService(handler, decoderConfig, subprotocols, allowedOrigins, closeTimeoutMillis);
    }
}
