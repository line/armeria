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

import static com.google.common.base.MoreObjects.firstNonNull;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketWriter;

import io.fabric8.kubernetes.client.http.WebSocket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

final class ArmeriaWebSocket implements WebSocket, Subscriber<WebSocketFrame> {
    private final WebSocketWriter writer;
    private final AtomicLong pending = new AtomicLong();
    private final WebSocket.Listener listener;
    private final StringBuilder textBuffer = new StringBuilder();
    private final Queue<byte[]> binaryBuffer = new ArrayDeque<>();

    @Nullable
    private Subscription inboundSubscription;
    @Nullable
    private WebSocketFrameType lastFrameType;

    ArmeriaWebSocket(WebSocketWriter writer, WebSocket.Listener listener) {
        this.writer = writer;
        this.listener = listener;
    }

    @Override
    public boolean send(ByteBuffer buffer) {
        // 'buffer' may be mutated by the caller, so we need to copy it.
        final ByteBuf data = Unpooled.copiedBuffer(buffer);
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
        writer.close(WebSocketCloseStatus.valueOf(code), firstNonNull(reason, "Closing"));
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
        request();
        listener.onOpen(this);
    }

    @Override
    public void onNext(WebSocketFrame webSocketFrame) {
        lastFrameType = webSocketFrame.type();
        final boolean last = webSocketFrame.isFinalFragment();
        switch (webSocketFrame.type()) {
            case TEXT:
                onText(webSocketFrame.text(), last);
                break;
            case BINARY:
                onBinary(webSocketFrame.array(), last);
                break;
            case CONTINUATION:
                if (lastFrameType == WebSocketFrameType.TEXT) {
                    onText(webSocketFrame.text(), last);
                } else if (lastFrameType == WebSocketFrameType.BINARY) {
                    onBinary(webSocketFrame.array(), last);
                } else {
                    throw new IllegalStateException("Unexpected frame type: " + lastFrameType + " (expected: " +
                                                    WebSocketFrameType.TEXT + " or " +
                                                    WebSocketFrameType.BINARY + ')');
                }
                break;
            case CLOSE:
                final CloseWebSocketFrame closeWebSocketFrame = (CloseWebSocketFrame) webSocketFrame;
                listener.onClose(this, closeWebSocketFrame.status().code(), closeWebSocketFrame.reasonPhrase());
                break;
            case PING:
            case PONG:
                request();
                break;
        }
    }

    private void onText(String text, boolean last) {
        textBuffer.append(text);
        if (last) {
            final String value = textBuffer.toString();
            textBuffer.setLength(0);
            listener.onMessage(this, value);
        } else {
            request();
        }
    }

    private void onBinary(byte[] bytes, boolean last) {
        binaryBuffer.add(bytes);
        if (last) {
            int size = 0;
            for (byte[] b : binaryBuffer) {
                size += b.length;
            }
            final ByteBuffer buffer = ByteBuffer.allocate(size);
            for (;;) {
                final byte[] binary = binaryBuffer.poll();
                if (binary == null) {
                    break;
                } else {
                    buffer.put(binary);
                }
            }
            buffer.flip();
            listener.onMessage(this, buffer);
        } else {
            request();
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
