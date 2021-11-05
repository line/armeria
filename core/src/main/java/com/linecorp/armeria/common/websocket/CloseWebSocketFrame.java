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

import com.linecorp.armeria.common.BinaryData;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.ByteArrayBinaryData;
import com.linecorp.armeria.internal.common.ByteBufBinaryData;

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
    private final String reason;

    CloseWebSocketFrame(byte[] binary) {
        this(new ByteArrayBinaryData(binary), Unpooled.wrappedBuffer(binary));
    }

    CloseWebSocketFrame(ByteBuf binary) {
        this(new ByteBufBinaryData(binary, true), binary);
    }

    CloseWebSocketFrame(WebSocketCloseStatus status, String reason) {
        this(new ByteBufBinaryData(binary(validateStatusCode(status.code()), reason), false), status, reason);
    }

    private CloseWebSocketFrame(BinaryData binaryData, ByteBuf binary) {
        this(binaryData, status(binary), reason(binary));
    }

    private CloseWebSocketFrame(BinaryData binaryData, WebSocketCloseStatus status, String reason) {
        super(WebSocketFrameType.CLOSE, binaryData, true, false, false);
        this.status = status;
        this.reason = reason;
    }

    /**
     * Returns {@link WebSocketCloseStatus}.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4">Status Codes</a>
     */
    public WebSocketCloseStatus status() {
        return status;
    }

    private static WebSocketCloseStatus status(ByteBuf binary) {
        if (binary.capacity() == 0) {
            throw new IllegalArgumentException("binary must have a close status.");
        }

        final int index = binary.readerIndex();
        final int statusCode = binary.getShort(0);
        binary.readerIndex(index);
        validateStatusCode(statusCode);
        return WebSocketCloseStatus.valueOf(statusCode);
    }

    /**
     * Returns the text that indicates the reason for closure.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4">Status Codes</a>
     */
    public String reason() {
        return reason;
    }

    private static String reason(ByteBuf binary) {
        if (binary.capacity() <= 2) {
            return "";
        }

        final int index = binary.readerIndex();
        binary.readerIndex(index + 2);
        final String reason = binary.toString(StandardCharsets.UTF_8);
        binary.readerIndex(index);
        return reason;
    }

    private static int validateStatusCode(int statusCode) {
        if (WebSocketCloseStatus.isValidStatusCode(statusCode)) {
            return statusCode;
        } else {
            throw new IllegalArgumentException("WebSocket close status code does NOT comply with RFC-6455: " +
                                               statusCode);
        }
    }

    private static ByteBuf binary(int statusCode, String reason) {
        final ByteBuf binary = Unpooled.buffer(2 + reason.length());
        binary.writeShort(statusCode);
        if (!reason.isEmpty()) {
            binary.writeCharSequence(reason, StandardCharsets.UTF_8);
        }

        binary.readerIndex(0);
        return binary;
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, reason) * 31 + super.hashCode();
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
               reason.equals(that.reason()) &&
               super.equals(obj);
    }

    @Override
    void addToString(ToStringHelper toStringHelper) {
        toStringHelper.add("status", status)
                      .add("reason", reason);
    }
}
