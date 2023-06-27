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

    public ClientRequestContext ctx() {
        return ctx;
    }

    public ResponseHeaders responseHeaders() {
        return responseHeaders;
    }

    public WebSocket inbound() {
        return inbound;
    }

    public void setOutbound(WebSocket outbound) {
        requireNonNull(outbound, "outbound");
        final StreamMessage<HttpData> streamMessage =
                outbound.map(webSocketFrame -> HttpData.wrap(encoder.encode(ctx, webSocketFrame)));
        outboundFuture.complete(streamMessage);
    }

    @Nullable
    public String subprotocol() {
        return subprotocol;
    }
}
