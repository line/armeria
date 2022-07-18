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
package com.linecorp.armeria.common.websocket;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.Bytes;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.ByteArrayBytes;
import com.linecorp.armeria.internal.common.ByteBufBytes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * A {@link WebSocketFrame} that is used to close the WebSocket connection.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.1">Close</a>
 */
@UnstableApi
public final class CloseWebSocketFrame extends DefaultWebSocketFrame {

    private final WebSocketCloseStatus status;
    private final String reasonPhase;

    CloseWebSocketFrame(byte[] data) {
        this(ByteArrayBytes.of(data), Unpooled.wrappedBuffer(data));
    }

    CloseWebSocketFrame(ByteBuf data) {
        this(ByteBufBytes.of(data, true), data);
    }

    CloseWebSocketFrame(WebSocketCloseStatus status, String reasonPhase) {
        this(ByteBufBytes.of(byteBuf(validateStatusCode(status.code()), reasonPhase), false),
             status, reasonPhase);
    }

    private CloseWebSocketFrame(Bytes bytes, ByteBuf data) {
        this(bytes, status(data), reasonPhase(data));
    }

    private CloseWebSocketFrame(Bytes bytes, WebSocketCloseStatus status, String reasonPhase) {
        super(WebSocketFrameType.CLOSE, bytes, true, false, false);
        this.status = status;
        this.reasonPhase = reasonPhase;
    }

    /**
     * Returns {@link WebSocketCloseStatus}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4">Status Codes</a>
     */
    public WebSocketCloseStatus status() {
        return status;
    }

    private static WebSocketCloseStatus status(ByteBuf data) {
        if (data.capacity() == 0) {
            data.release();
            throw new IllegalArgumentException("data must have a close status.");
        }

        final int index = data.readerIndex();
        final int statusCode = data.getShort(0);
        data.readerIndex(index);
        try {
            validateStatusCode(statusCode);
        } catch (Throwable t) {
            data.release();
            throw t;
        }
        return WebSocketCloseStatus.valueOf(statusCode);
    }

    /**
     * Returns the text that indicates the reason for closure.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4">Status Codes</a>
     */
    public String reasonPhase() {
        return reasonPhase;
    }

    private static String reasonPhase(ByteBuf data) {
        if (data.capacity() <= 2) {
            return "";
        }

        final int index = data.readerIndex();
        data.readerIndex(index + 2);
        final String reasonPhase = data.toString(StandardCharsets.UTF_8);
        data.readerIndex(index);
        return reasonPhase;
    }

    private static int validateStatusCode(int statusCode) {
        if (WebSocketCloseStatus.isValidStatusCode(statusCode)) {
            return statusCode;
        } else {
            throw new IllegalArgumentException("WebSocket close status code does NOT comply with RFC-6455: " +
                                               statusCode);
        }
    }

    private static ByteBuf byteBuf(int statusCode, String reasonPhase) {
        final ByteBuf byteBuf = Unpooled.buffer(2 + reasonPhase.length());
        byteBuf.writeShort(statusCode);
        if (!reasonPhase.isEmpty()) {
            byteBuf.writeCharSequence(reasonPhase, StandardCharsets.UTF_8);
        }

        byteBuf.readerIndex(0);
        return byteBuf;
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, reasonPhase) * 31 + super.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof CloseWebSocketFrame)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final CloseWebSocketFrame that = (CloseWebSocketFrame) obj;
        return status.equals(that.status()) &&
               reasonPhase.equals(that.reasonPhase()) &&
               super.equals(obj);
    }

    @Override
    void addToString(ToStringHelper toStringHelper) {
        toStringHelper.add("status", status)
                      .add("reasonPhase", reasonPhase);
    }
}
