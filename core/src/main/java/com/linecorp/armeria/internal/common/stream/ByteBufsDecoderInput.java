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

import com.linecorp.armeria.common.stream.StreamDecoderInput;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

public final class ByteBufsDecoderInput implements StreamDecoderInput {

    private final ByteBufAllocator alloc;
    private final Queue<ByteBuf> queue;
    private int readableBytes;

    private boolean closed;

    public ByteBufsDecoderInput(ByteBufAllocator alloc) {
        this.alloc = alloc;
        queue = new ArrayDeque<>(4);
    }

    public boolean add(ByteBuf byteBuf) {
        final int readableBytes = byteBuf.readableBytes();
        if (closed || readableBytes == 0) {
            byteBuf.release();
            return false;
        }

        queue.add(byteBuf);
        this.readableBytes += readableBytes;
        return true;
    }

    @Override
    public int readableBytes() {
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

        this.readableBytes--;
        return value;
    }

    @Override
    public int readUnsignedShort() {
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = firstBuf.readableBytes();
        if (readableBytes >= 2) {
            final int value = firstBuf.readUnsignedShort();
            if (readableBytes == 2) {
                queue.remove();
                firstBuf.release();
            }
            this.readableBytes -= 2;
            return value;
        }
        return StreamDecoderInput.super.readUnsignedShort();
    }

    @Override
    public int readInt() {
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = firstBuf.readableBytes();
        final int value;
        if (readableBytes >= 4) {
            value = firstBuf.readInt();
            if (readableBytes == 4) {
                queue.remove();
                firstBuf.release();
            }
        } else {
            value = readIntSlow();
        }

        this.readableBytes -= 4;
        return value;
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
    public long readLong() {
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = firstBuf.readableBytes();
        if (readableBytes >= 8) {
            final long value = firstBuf.readLong();
            if (readableBytes == 8) {
                queue.remove();
                firstBuf.release();
            }
            this.readableBytes -= 8;
            return value;
        }

        // readableBytes is decreased in readInt.
        return (long) readInt() << 32 | readInt();
    }

    @Override
    public ByteBuf readBytes(int length) {
        if (length == 0) {
            return Unpooled.EMPTY_BUFFER;
        }
        checkArgument(length > 0, "length %s (expected: length > 0)", length);
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = firstBuf.readableBytes();
        final ByteBuf byteBuf;
        if (readableBytes == length) {
            byteBuf = queue.remove();
        } else if (readableBytes > length) {
            byteBuf = firstBuf.readRetainedSlice(length);
        } else {
            byteBuf = readBytesSlow(length);
        }

        this.readableBytes -= length;
        return byteBuf;
    }

    @Override
    public void readBytes(byte[] dst) {
        final int length = dst.length;
        if (length == 0) {
            return;
        }
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = firstBuf.readableBytes();
        if (readableBytes == length) {
            queue.remove().readBytes(dst).release();
        } else if (readableBytes > length) {
            firstBuf.readBytes(dst, 0, length);
        } else {
            readBytesSlow(dst);
        }

        this.readableBytes -= length;
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

    private void readBytesSlow(byte[] dst) {
        int remaining = dst.length;
        for (final Iterator<ByteBuf> it = queue.iterator(); it.hasNext();) {
            final ByteBuf buf = it.next();
            final int readableBytes = buf.readableBytes();
            assert readableBytes > 0 : buf;

            final int readSize = Math.min(remaining, readableBytes);
            buf.readBytes(dst, dst.length - remaining, readSize);
            if (readableBytes == readSize) {
                it.remove();
                buf.release();
            }

            remaining -= readSize;
            if (remaining == 0) {
                return;
            }
        }

        throw newEndOfInputException();
    }

    @Override
    public byte getByte(int index) {
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = firstBuf.readableBytes();
        if (readableBytes > index) {
            return firstBuf.getByte(firstBuf.readerIndex() + index);
        } else {
            return getByteSlow(index - readableBytes);
        }
    }

    private byte getByteSlow(int remaining) {
        final Iterator<ByteBuf> it = queue.iterator();
        // The first buf was already checked in getByte().
        it.next();

        while (it.hasNext()) {
            final ByteBuf buf = it.next();
            final int readableBytes = buf.readableBytes();
            if (readableBytes > remaining) {
                return buf.getByte(buf.readerIndex() + remaining);
            } else {
                remaining -= readableBytes;
            }
        }

        throw newEndOfInputException();
    }

    @Override
    public void skipBytes(int length) {
        if (length == 0) {
            return;
        }
        final ByteBuf firstBuf = queue.peek();
        if (firstBuf == null) {
            throw newEndOfInputException();
        }

        final int readableBytes = firstBuf.readableBytes();
        if (readableBytes > length) {
            firstBuf.skipBytes(length);
        } else {
            skipBytesSlow(length - readableBytes);
        }
        this.readableBytes -= length;
    }

    private void skipBytesSlow(int remaining) {
        // The first buf was already checked in skipBytes().
        queue.remove().release();

        if (remaining <= 0) {
            // Nothing to skip
            return;
        }

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
        readableBytes = 0;
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
