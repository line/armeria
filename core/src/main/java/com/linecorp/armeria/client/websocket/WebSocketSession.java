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

import org.reactivestreams.Publisher;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;

/**
 * A WebSocket session that is created after {@link WebSocketClient#connect(String)} succeeds.
 * You can start sending {@link WebSocketFrame}s via {@link #setOutbound(Publisher)}. You can also subscribe to
 * {@link #inbound()} to receive {@link WebSocketFrame}s from the server.
 */
@UnstableApi
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
    public ClientRequestContext context() {
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
     * Returns the {@link WebSocket} that is used to receive WebSocket frames from the server.
     */
    public WebSocket inbound() {
        return inbound;
    }

    /**
     * Returns the {@link WebSocketWriter} that is used to send WebSocket frames to the server.
     *
     * @throws IllegalStateException if this method or {@link #setOutbound(Publisher)} has been called already.
     */
    public WebSocketWriter outbound() {
        final WebSocketWriter writer = WebSocket.streaming();
        setOutbound(writer);
        return writer;
    }

    /**
     * Sets the {@link WebSocket} that is used to send WebSocket frames to the server.
     *
     * @throws IllegalStateException if this method or {@link #outbound()} has been called already.
     */
    public void setOutbound(Publisher<? extends WebSocketFrame> outbound) {
        requireNonNull(outbound, "outbound");
        if (outboundFuture.isDone()) {
            if (outbound instanceof StreamMessage) {
                ((StreamMessage<?>) outbound).abort();
            }
            throw new IllegalStateException("outbound() or setOutbound() has been already called.");
        }
        final StreamMessage<? extends WebSocketFrame> streamMessage;
        if (outbound instanceof StreamMessage) {
            streamMessage = (StreamMessage<? extends WebSocketFrame>) outbound;
        } else {
            streamMessage = new PublisherBasedStreamMessage<>(outbound);
        }

        if (!outboundFuture.complete(
                streamMessage.map(webSocketFrame -> HttpData.wrap(encoder.encode(ctx, webSocketFrame))))) {
            streamMessage.abort();
            throw new IllegalStateException("outbound() or setOutbound() has been already called.");
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("ctx", ctx)
                          .add("responseHeaders", responseHeaders)
                          .add("subprotocol", subprotocol)
                          .add("inbound", inbound)
                          .add("outboundFuture", outboundFuture)
                          .add("encoder", encoder)
                          .toString();
    }
}
