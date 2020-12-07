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

package com.linecorp.armeria.internal.common.stream;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

import com.linecorp.armeria.common.stream.HttpDeframerInput;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public final class ByteBufDeframerInput implements HttpDeframerInput {

    private final ByteBufAllocator alloc;
    private final Queue<ByteBuf> queue;

    private boolean closed;

    public ByteBufDeframerInput(ByteBufAllocator alloc) {
        this.alloc = alloc;
        queue = new ArrayDeque<>();
    }

    public boolean add(ByteBuf byteBuf) {
        if (closed || !byteBuf.isReadable()) {
            byteBuf.release();
            return false;
        }

        queue.add(byteBuf);
        return true;
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
        final ByteBuf buf = queue.peek();

        if (buf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = buf.readableBytes();
        final byte value = buf.readByte();
        if (readableBytes == 1) {
            queue.remove();
            buf.release();
        }
        return value;
    }

    @Override
    public int readInt() {
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = firstBuf.readableBytes();
        if (readableBytes >= 4) {
            final int value = firstBuf.readInt();
            if (readableBytes == 4) {
                queue.remove();
                firstBuf.release();
            }
            return value;
        }

        return readIntSlow();
    }

    private int readIntSlow() {
        int value = 0;
        int remaining = 4;
        for (final Iterator<ByteBuf> it = queue.iterator(); it.hasNext();) {
            final ByteBuf buf = it.next();
            final int readableBytes = buf.readableBytes();
            final int readSize = Math.min(remaining, readableBytes);

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
            if (readSize == readableBytes) {
                it.remove();
                buf.release();
            }
            remaining -= readSize;
            if (remaining == 0) {
                return value;
            }
        }

        throw newEndOfInputException();
    }

    @Override
    public ByteBuf readBytes(int length) {
        checkArgument(length > 0, "length %s (expected: length > 0)", length);
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = firstBuf.readableBytes();
        if (readableBytes == length) {
            return queue.remove();
        }
        if (readableBytes > length) {
            return firstBuf.readRetainedSlice(length);
        }

        return readBytesSlow(length);
    }

    private ByteBuf readBytesSlow(int length) {
        final ByteBuf value = alloc.buffer(length);
        int remaining = length;
        for (final Iterator<ByteBuf> it = queue.iterator(); it.hasNext();) {
            final ByteBuf buf = it.next();
            final int readableBytes = buf.readableBytes();
            assert readableBytes > 0 : buf;

            final int readSize = Math.min(remaining, readableBytes);
            value.writeBytes(buf, readSize);
            if (readableBytes == readSize) {
                it.remove();
                buf.release();
            }

            remaining -= readSize;
            if (remaining == 0) {
                return value;
            }
        }

        value.release();
        throw newEndOfInputException();
    }

    @Override
    public byte getByte(int index) {
        for (ByteBuf buf : queue) {
            final int readableBytes = buf.readableBytes();
            if (readableBytes > index) {
                return buf.getByte(buf.readerIndex() + index);
            } else {
                index -= readableBytes;
            }
        }

        throw newEndOfInputException();
    }

    @Override
    public void skipBytes(int length) {
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = firstBuf.readableBytes();
        if (readableBytes > length) {
            firstBuf.skipBytes(length);
        } else {
            queue.remove();
            firstBuf.release();
            final int remaining = length - readableBytes;
            if (remaining > 0) {
                skipBytesSlow(remaining);
            }
        }
    }

    private void skipBytesSlow(int remaining) {
        for (final Iterator<ByteBuf> it = queue.iterator(); it.hasNext();) {
            final ByteBuf buf = it.next();
            final int readableBytes = buf.readableBytes();
            if (readableBytes > remaining) {
                buf.skipBytes(remaining);
                return;
            } else {
                buf.release();
                it.remove();
                remaining -= readableBytes;
                if (remaining == 0) {
                    return;
                }
            }
        }

        throw newEndOfInputException();
    }

    private static IllegalStateException newEndOfInputException() {
        return new IllegalStateException("end of deframer input");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
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
