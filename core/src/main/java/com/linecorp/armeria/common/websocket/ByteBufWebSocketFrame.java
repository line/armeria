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

import static com.linecorp.armeria.internal.common.ByteArrayUtil.generatePreview;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

final class ByteBufWebSocketFrame extends AbstractWebSocketFrame {

    private final ByteBuf buf;
    @Nullable
    private byte[] array;

    ByteBufWebSocketFrame(WebSocketFrameType type, ByteBuf buf, boolean finalFragment) {
        super(type, finalFragment);
        this.buf = requireNonNull(buf, "buf");
    }

    @Override
    public byte[] array() {
        if (array != null) {
            return array;
        }

        final ByteBuf buf = this.buf;
        final int length = buf.readableBytes();
        if (isPooled()) {
            buf.touch(this);
            // We don't use the pooled buffer's underlying array here,
            // because it will be in use by others when 'buf' is released.
        } else if (buf.hasArray() && buf.arrayOffset() == 0 && buf.readerIndex() == 0) {
            final byte[] bufArray = buf.array();
            if (bufArray.length == length) {
                return array = bufArray;
            }
        }

        return array = ByteBufUtil.getBytes(buf, buf.readerIndex(), length);
    }

    @Override
    public int dataLength() {
        return buf.readableBytes();
    }

    @Override
    public boolean isPooled() {
        return true;
    }

    @Override
    public ByteBuf byteBuf(ByteBufAccessMode mode) {
        return PooledObjects.byteBuf(buf, mode);
    }

    @Override
    public void close() {
        buf.release();
    }

    @Override
    public int hashCode() {
        int hash = Objects.hash(type(), isFinalFragment(), dataLength());

        // Calculate the hash code from the first 32 bytes.
        final int bufStart = buf.readerIndex();
        final int bufEnd = bufStart + Math.min(32, buf.readableBytes());
        for (int i = bufStart; i < bufEnd; i++) {
            hash = hash * 31 + buf.getByte(i);
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ByteBufWebSocketFrame)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final ByteBufWebSocketFrame that = (ByteBufWebSocketFrame) obj;
        return type() == that.type() &&
               isFinalFragment() == that.isFinalFragment() &&
               dataLength() == that.dataLength() &&
               ByteBufUtil.equals(buf, that.byteBuf());
    }

    @Override
    public String toString() {
        final int length = buf.readableBytes();

        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder strBuf = tempThreadLocals.stringBuilder();
            strBuf.append('{').append(length).append("B, pooled, ");

            if (buf.refCnt() == 0) {
                return strBuf.append("closed}").toString();
            }

            final byte[] array = this.array;
            final ByteBuf buf = this.buf;

            this.array = generatePreview(strBuf, array, buf, length, false);
            strBuf.append('}');
            return strBuf.toString();
        }
    }
}
