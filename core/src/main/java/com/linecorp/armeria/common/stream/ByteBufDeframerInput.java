/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayDeque;
import java.util.Queue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

final class ByteBufDeframerInput implements HttpDeframerInput {

    private final ByteBufAllocator alloc;
    private final Queue<ByteBuf> queue;

    ByteBufDeframerInput(ByteBufAllocator alloc) {
        this.alloc = alloc;
        queue = new ArrayDeque<>();
    }

    void add(ByteBuf byteBuf) {
        queue.add(byteBuf);
    }

    @Override
    public int readableBytes() {
        if (queue.isEmpty()) {
            return 0;
        }

        int readableBytes = 0;
        for (ByteBuf buf : queue) {
            readableBytes += buf.readableBytes();
        }
        return readableBytes;
    }

    @Override
    public byte readByte() {
        for (ByteBuf buf : queue) {
            if (!buf.isReadable()) {
                continue;
            }
            return buf.readByte();
        }
        throw newEndOfInputException();
    }

    @Override
    public int readInt() {
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        if (firstBuf.readableBytes() >= 4) {
            return firstBuf.readInt();
        }

        return readIntSlow();
    }

    private int readIntSlow() {
        int value = 0;
        int remained = 4;
        for (ByteBuf buf : queue) {
            if (!buf.isReadable()) {
                continue;
            }
            final int readSize = Math.min(remained, buf.readableBytes());
            if (readSize == 4) {
                return buf.readInt();
            }

            value <<= 8 * readSize;
            switch (readSize) {
                case 1:
                    value |= buf.readUnsignedByte();
                    break;
                case 2:
                    value |= buf.readUnsignedShort();
                    break;
                case 3:
                    value |= buf.readUnsignedMedium();
                    break;
                default:
                    throw new Error(); // Should not reach here.
            }
            remained -= readSize;
            if (remained == 0) {
                break;
            }
        }

        if (remained == 0) {
            return value;
        } else {
            throw newEndOfInputException();
        }
    }

    @Override
    public ByteBuf readBytes(int length) {
        checkArgument(length > 0, "length %s (expected: length > 0)", length);
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        if (firstBuf.readableBytes() >= length) {
            return firstBuf.readRetainedSlice(length);
        }

        return readBytesSlow(length);
    }

    private ByteBuf readBytesSlow(int length) {
        ByteBuf value = null;
        int remained = length;
        for (ByteBuf buf : queue) {
            if (!buf.isReadable()) {
                continue;
            }

            final int readSize = Math.min(remained, buf.readableBytes());
            if (readSize == length) {
                return buf.readRetainedSlice(readSize);
            } else {
                if (value == null) {
                    value = alloc.buffer(length);
                }
                value.writeBytes(buf, readSize);
                remained -= readSize;
                if (remained == 0) {
                    break;
                }
            }
        }

        if (remained > 0 || value == null) {
            ReferenceCountUtil.release(value);
            throw newEndOfInputException();
        }

        return value;
    }

    /**
     * Discards the {@link ByteBuf}s that have been fully consumed and are not readable anymore.
     */
    void discardReadBytes() {
        for (;;) {
            final ByteBuf buf = queue.peek();
            if (buf != null && !buf.isReadable()) {
                queue.remove().release();
            } else {
                break;
            }
        }
    }

    private static IllegalStateException newEndOfInputException() {
        return new IllegalStateException("end of deframer input");
    }

    @Override
    public void close() {
        for (;;) {
            final ByteBuf buf = queue.poll();
            if (buf != null) {
                buf.release();
            } else {
                break;
            }
        }
    }
}
