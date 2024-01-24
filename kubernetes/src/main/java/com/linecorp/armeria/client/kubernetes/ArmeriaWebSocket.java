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

package com.linecorp.armeria.client.kubernetes;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;

import io.fabric8.kubernetes.client.http.WebSocket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

final class ArmeriaWebSocket implements WebSocket, Subscriber<WebSocketFrame> {
    private final WebSocketWriter writer;
    private final AtomicLong pending = new AtomicLong();
    private final WebSocket.Listener listener;

    @Nullable
    private Subscription inboundSubscription;

    ArmeriaWebSocket(WebSocketWriter writer, WebSocket.Listener listener) {
        this.writer = writer;
        this.listener = listener;
    }

    @Override
    public boolean send(ByteBuffer buffer) {
        // 'buffer' may be mutated by the caller, so we need to copy it.
        final ByteBufAllocator alloc = ClientRequestContext.mapCurrent(RequestContext::alloc,
                                                                       () -> ByteBufAllocator.DEFAULT);
        final ByteBuf data = alloc.buffer(buffer.remaining()).writeBytes(buffer.duplicate());
        final int dataLength = data.readableBytes();
        pending.addAndGet(dataLength);
        final boolean success = writer.tryWrite(WebSocketFrame.ofPooledBinary(data));
        if (success) {
            writer.whenConsumed().thenRun(() -> pending.addAndGet(-dataLength));
        } else {
            pending.addAndGet(-dataLength);
        }
        return success;
    }

    @Override
    public boolean sendClose(int code, @Nullable String reason) {
        if (!writer.isOpen()) {
            return false;
        }
        if (reason == null) {
            writer.close(WebSocketCloseStatus.valueOf(code));
        } else {
            writer.close(WebSocketCloseStatus.valueOf(code), reason);
        }
        return true;
    }

    @Override
    public long queueSize() {
        return pending.get();
    }

    @Override
    public void request() {
        // request() is invoked after listener.onOpen() is called.
        assert inboundSubscription != null;
        inboundSubscription.request(1);
    }

    @Override
    public void onSubscribe(Subscription s) {
        inboundSubscription = s;
        listener.onOpen(this);
        request();
    }

    @Override
    public void onNext(WebSocketFrame webSocketFrame) {
        switch (webSocketFrame.type()) {
            case TEXT:
                // The listener will call `request()` when it consumes the buffer.
                // See https://github.com/fabric8io/kubernetes-client/blob/56a6c2c4f336cc6f64c19029a55c2d3d0289344f/kubernetes-client/src/main/java/io/fabric8/kubernetes/client/dsl/internal/WatcherWebSocketListener.java
                listener.onMessage(this, webSocketFrame.text());
                break;
            case BINARY:
                listener.onMessage(this, webSocketFrame.byteBuf().nioBuffer());
                break;
            case CLOSE:
                final CloseWebSocketFrame closeWebSocketFrame = (CloseWebSocketFrame) webSocketFrame;
                listener.onClose(this, closeWebSocketFrame.status().code(), closeWebSocketFrame.reasonPhrase());
                break;
            case PING:
            case PONG:
                request();
                break;
            case CONTINUATION:
                throw new Error(); // Never reach here. aggregateContinuation() is enabled.
        }
    }

    @Override
    public void onError(Throwable cause) {
        listener.onError(this, cause);
    }

    @Override
    public void onComplete() {
        // A WebSocket session is completed by a CLOSE frame.
    }
}
