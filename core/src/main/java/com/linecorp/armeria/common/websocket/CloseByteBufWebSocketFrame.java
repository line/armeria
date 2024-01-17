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

package com.linecorp.armeria.common.websocket;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

final class CloseByteBufWebSocketFrame extends ByteBufWebSocketFrame implements CloseWebSocketFrame {

    private final WebSocketCloseStatus status;
    @Nullable
    private final String reasonPhrase;

    CloseByteBufWebSocketFrame(byte[] data) {
        this(Unpooled.wrappedBuffer(data), false);
    }

    CloseByteBufWebSocketFrame(ByteBuf data, boolean pooled) {
        this(data, pooled, status(data), reasonPhrase(data));
    }

    CloseByteBufWebSocketFrame(WebSocketCloseStatus status, String reasonPhrase) {
        this(createByteBuf(validateStatusCode(status.code()), requireNonNull(reasonPhrase, "reasonPhrase")),
             false, status, reasonPhrase);
    }

    private CloseByteBufWebSocketFrame(ByteBuf data, boolean pooled,
                                       WebSocketCloseStatus status, @Nullable String reasonPhrase) {
        super(data, pooled, WebSocketFrameType.CLOSE, true);
        this.status = status;
        this.reasonPhrase = reasonPhrase;
    }

    @Override
    public WebSocketCloseStatus status() {
        return status;
    }

    private static WebSocketCloseStatus status(ByteBuf data) {
        if (data.capacity() == 0) {
            data.release();
            throw new IllegalArgumentException("data must have a close status.");
        }

        final int index = data.readerIndex();
        final int statusCode = data.readShort();
        data.readerIndex(index);
        try {
            validateStatusCode(statusCode);
        } catch (Throwable t) {
            data.release();
            throw t;
        }
        return WebSocketCloseStatus.valueOf(statusCode);
    }

    private static int validateStatusCode(int statusCode) {
        if (WebSocketCloseStatus.isValidStatusCode(statusCode)) {
            return statusCode;
        } else {
            throw new IllegalArgumentException(
                    "WebSocket close status code does NOT comply with RFC 6455. code: " + statusCode);
        }
    }

    @Override
    public String reasonPhrase() {
        return reasonPhrase;
    }

    @Nullable
    private static String reasonPhrase(ByteBuf data) {
        if (data.capacity() <= 2) {
            return null;
        }

        final int index = data.readerIndex();
        if (index + 2 >= data.writerIndex()) { // No reasonPhrase
            return null;
        }
        data.readerIndex(index + 2);
        final String reasonPhrase = data.toString(StandardCharsets.UTF_8);
        data.readerIndex(index);
        return reasonPhrase;
    }

    private static ByteBuf createByteBuf(int statusCode, String reasonPhrase) {
        final ByteBuf byteBuf = Unpooled.buffer(2 + reasonPhrase.length());
        byteBuf.writeShort(statusCode);
        if (!reasonPhrase.isEmpty()) {
            byteBuf.writeCharSequence(reasonPhrase, StandardCharsets.UTF_8);
        }

        byteBuf.readerIndex(0);
        return byteBuf;
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + Objects.hash(status, reasonPhrase);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof CloseByteBufWebSocketFrame)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final CloseByteBufWebSocketFrame that = (CloseByteBufWebSocketFrame) obj;
        return status.equals(that.status()) &&
               reasonPhrase.equals(that.reasonPhrase()) &&
               super.equals(obj);
    }

    @Override
    void addToString(ToStringHelper toStringHelper) {
        toStringHelper.add("status", status)
                      .add("reasonPhrase", reasonPhrase);
    }
}
