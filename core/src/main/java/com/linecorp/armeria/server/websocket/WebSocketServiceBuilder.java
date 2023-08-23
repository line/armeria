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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.internal.common.websocket.WebSocketUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceConfig;

/**
 * Builds a {@link WebSocketService}.
 * This service has the different default configs from a normal {@link HttpService}. Here are the differences:
 * <ul>
 *   <li>{@link ServiceConfig#requestTimeoutMillis()} is
 *       {@value WebSocketUtil#DEFAULT_REQUEST_RESPONSE_TIMEOUT_MILLIS}.</li>
 *   <li>{@link ServiceConfig#maxRequestLength()} is
 *       {@value WebSocketUtil#DEFAULT_MAX_REQUEST_RESPONSE_LENGTH}.</li>
 *   <li>{@link ServiceConfig#requestAutoAbortDelayMillis()} is
 *       {@value WebSocketUtil#DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS}.</li>
 * </ul>
 */
@UnstableApi
public final class WebSocketServiceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServiceBuilder.class);

    private static final String ANY_ORIGIN = "*";

    static final int DEFAULT_MAX_FRAME_PAYLOAD_LENGTH = 65535; // 64 * 1024 -1

    private final WebSocketServiceHandler handler;

    private int maxFramePayloadLength = DEFAULT_MAX_FRAME_PAYLOAD_LENGTH;
    private boolean allowMaskMismatch;
    private Set<String> subprotocols = ImmutableSet.of();
    private Set<String> allowedOrigins = ImmutableSet.of();

    WebSocketServiceBuilder(WebSocketServiceHandler handler) {
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
     * Sets whether the decoder allows to loosen the masking requirement on received frames.
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
     * Sets the allowed origins. The same-origin is allowed by default.
     * Specify {@value ANY_ORIGIN} to allow any origins.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigins(String... allowedOrigins) {
        return allowedOrigins(ImmutableSet.copyOf(requireNonNull(allowedOrigins, "allowedOrigins")));
    }

    /**
     * Sets the allowed origins. The same-origin is allowed by default.
     * Specify {@value ANY_ORIGIN} to allow any origins.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-10.2">Origin Considerations</a>
     */
    public WebSocketServiceBuilder allowedOrigins(Iterable<String> allowedOrigins) {
        this.allowedOrigins = validateOrigins(allowedOrigins);
        return this;
    }

    private static Set<String> validateOrigins(Iterable<String> allowedOrigins) {
        //TODO(minwoox): Dedup the same logic in cors service.
        final Set<String> copied = ImmutableSet.copyOf(requireNonNull(allowedOrigins, "allowedOrigins"));
        checkArgument(!copied.isEmpty(), "allowedOrigins is empty. (expected: non-empty)");
        if (copied.contains(ANY_ORIGIN)) {
            if (copied.size() > 1) {
                logger.warn("Any origin (*) has been already included. Other origins ({}) will be ignored.",
                            copied.stream()
                                  .filter(c -> !ANY_ORIGIN.equals(c))
                                  .collect(Collectors.joining(",")));
            }
        }
        return copied;
    }

    /**
     * Returns a newly-created {@link WebSocketService} with the properties set so far.
     */
    public WebSocketService build() {
        return new WebSocketService(handler, maxFramePayloadLength, allowMaskMismatch,
                                    subprotocols, allowedOrigins, allowedOrigins.contains(ANY_ORIGIN));
    }
}
