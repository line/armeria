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

import static com.linecorp.armeria.internal.common.ByteArrayUtil.appendPreviews;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Objects;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;

final class ByteArrayWebSocketFrame extends AbstractWebSocketFrame {

    private final byte[] array;

    ByteArrayWebSocketFrame(WebSocketFrameType type, byte[] array, boolean finalFragment) {
        super(type, finalFragment);
        this.array = requireNonNull(array, "array");
    }

    @Override
    public byte[] array() {
        return array;
    }

    @Override
    public int dataLength() {
        return array.length;
    }

    @Override
    public boolean isPooled() {
        return false;
    }

    @Override
    public ByteBuf byteBuf(ByteBufAccessMode mode) {
        return PooledObjects.byteBuf(array, mode);
    }

    @Override
    public void close() {}

    @Override
    public int hashCode() {
        int hash = Objects.hash(type(), isFinalFragment(), dataLength());

        // Calculate the hash code from the first 32 bytes.
        final int end = Math.min(32, dataLength());
        for (int i = 0; i < end; i++) {
            hash = hash * 31 + array[i];
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ByteArrayWebSocketFrame)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final ByteArrayWebSocketFrame that = (ByteArrayWebSocketFrame) obj;
        return type() == that.type() &&
               isFinalFragment() == that.isFinalFragment() &&
               dataLength() == that.dataLength() &&
               Arrays.equals(array, that.array());
    }

    @Override
    public String toString() {
        if (array.length == 0) {
            return "{0B}";
        }

        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append('{').append(array.length).append("B, ");
            return appendPreviews(buf, array, 0, Math.min(16, array.length)).append('}')
                                                                            .toString();
        }
    }
}
