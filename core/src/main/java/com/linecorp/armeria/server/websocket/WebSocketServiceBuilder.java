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
package com.linecorp.armeria.server.websocket;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;

/**
 * Builds a {@link WebSocketService}.
 */
@UnstableApi
public final class WebSocketServiceBuilder {

    static final int DEFAULT_MAX_FRAME_PAYLOAD_LENGTH = 65535; // 64 * 1024 -1

    private final WebSocketHandler handler;

    private int maxFramePayloadLength = DEFAULT_MAX_FRAME_PAYLOAD_LENGTH;
    private boolean allowMaskMismatch;
    private Set<String> subprotocols = ImmutableSet.of();
    private Set<String> allowedOrigins = ImmutableSet.of();

    WebSocketServiceBuilder(WebSocketHandler handler) {
        this.handler = requireNonNull(handler, "handler");
    }

    /**
     * Sets the maximum length of a frame's payload. If the size of a payload data exceeds the value,
     * {@link WebSocketCloseStatus#MESSAGE_TOO_BIG} is sent to the peer.
     * {@value DEFAULT_MAX_FRAME_PAYLOAD_LENGTH} is used by default.
     */
    public WebSocketServiceBuilder maxFramePayloadLength(int maxFramePayloadLength) {
        checkArgument(maxFramePayloadLength > 0,
                      "maxFramePayloadLength: %s (expected: > 0)", maxFramePayloadLength);
        this.maxFramePayloadLength = maxFramePayloadLength;
        return this;
    }

    /**
     * Sets whether the decoder allow to loosen the masking requirement on received frames.
     * It's not allowed by default.
     */
    public WebSocketServiceBuilder allowMaskMismatch(boolean allowMaskMismatch) {
        this.allowMaskMismatch = allowMaskMismatch;
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
     * Sets the allowed origins. All origins are allowed by default.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigins(String... allowedOrigins) {
        return allowedOrigins(ImmutableSet.copyOf(requireNonNull(allowedOrigins, "allowedOrigins")));
    }

    /**
     * Sets the allowed origins. All origins are allowed by default.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigins(Iterable<String> allowedOrigins) {
        this.allowedOrigins = ImmutableSet.copyOf(requireNonNull(allowedOrigins, "allowedOrigins"));
        return this;
    }

    /**
     * Returns a newly-created {@link WebSocketService} with the properties set so far.
     */
    public WebSocketService build() {
        return new WebSocketService(handler, maxFramePayloadLength, allowMaskMismatch,
                                    subprotocols, allowedOrigins);
    }
}