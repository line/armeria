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

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;

/**
 * A WebSocket session.
 */
public final class WebSocketSession {

    private final ClientRequestContext ctx;
    private final ResponseHeaders responseHeaders;
    @Nullable
    private final String subprotocol;
    private final WebSocket inbound;
    private final CompletableFuture<StreamMessage<HttpData>> outboundFuture;
    private final WebSocketFrameEncoder encoder;

    WebSocketSession(ClientRequestContext ctx, ResponseHeaders responseHeaders, WebSocket inbound,
                     CompletableFuture<StreamMessage<HttpData>> outboundFuture,
                     WebSocketFrameEncoder encoder) {
        this.ctx = ctx;
        this.responseHeaders = responseHeaders;
        subprotocol = responseHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        this.inbound = inbound;
        this.outboundFuture = outboundFuture;
        this.encoder = encoder;
    }

    /**
     * Returns the {@link ClientRequestContext}.
     */
    public ClientRequestContext ctx() {
        return ctx;
    }

    /**
     * Returns the {@link ResponseHeaders}.
     */
    public ResponseHeaders responseHeaders() {
        return responseHeaders;
    }

    /**
     * Returns the subprotocol negotiated between the client and the server.
     */
    @Nullable
    public String subprotocol() {
        return subprotocol;
    }

    /**
     * Returns the {@link WebSocket} which is used to receive WebSocket frames from the server.
     */
    public WebSocket inbound() {
        return inbound;
    }

    /**
     * Sets the {@link WebSocket} which is used to send WebSocket frames to the server.
     */
    public void send(WebSocket outbound) {
        requireNonNull(outbound, "outbound");
        final StreamMessage<HttpData> streamMessage =
                outbound.map(webSocketFrame -> HttpData.wrap(encoder.encode(ctx, webSocketFrame)));
        outboundFuture.complete(streamMessage);
    }
}
