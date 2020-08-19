/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.common;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.nio.charset.Charset;

import javax.annotation.Nullable;

import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ResourceLeakHint;
import it.unimi.dsi.fastutil.io.FastByteArrayInputStream;

/**
 * A {@link ByteBuf}-based {@link HttpData}.
 */
final class ByteBufHttpData implements HttpData, ResourceLeakHint {

    private static final int FLAG_POOLED = 1;
    private static final int FLAG_END_OF_STREAM = 2;
    private static final int FLAG_CLOSED = 4;

    private final ByteBuf buf;
    @Nullable
    private byte[] array;
    private int flags;

    ByteBufHttpData(ByteBuf buf, boolean pooled) {
        this(buf, pooled ? FLAG_POOLED : 0, null);
    }

    private ByteBufHttpData(ByteBuf buf, int flags, @Nullable byte[] array) {
        this.buf = buf;
        this.array = array;
        this.flags = flags;
    }

    @Override
    public byte[] array() {
        if (array != null) {
            return array;
        }

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
    public int length() {
        return buf.readableBytes();
    }

    @Override
    public String toString(Charset charset) {
        requireNonNull(charset, "charset");
        if (array != null) {
            return new String(array, charset);
        } else {
            return buf.toString(charset);
        }
    }

    @Override
    public String toString() {
        return toString(false);
    }

    private String toString(boolean hint) {
        final int length = buf.readableBytes();

        final StringBuilder strBuf = TemporaryThreadLocals.get().stringBuilder();
        strBuf.append('{').append(length).append("B, ");

        if (isEndOfStream()) {
            strBuf.append("EOS, ");
        }
        if (isPooled()) {
            strBuf.append("pooled, ");
        }
        if ((flags & FLAG_CLOSED) != 0) {
            if (buf.refCnt() == 0) {
                return strBuf.append("closed}").toString();
            } else {
                strBuf.append("closed, ");
            }
        }

        // Generate the preview array.
        final int previewLength = Math.min(16, length);
        byte[] array = this.array;
        final int offset;
        if (array == null) {
            try {
                if (buf.hasArray()) {
                    array = buf.array();
                    offset = buf.arrayOffset() + buf.readerIndex();
                } else if (!hint) {
                    array = ByteBufUtil.getBytes(buf, buf.readerIndex(), previewLength);
                    offset = 0;
                    if (previewLength == length) {
                        this.array = array;
                    }
                } else {
                    // Can't call getBytes() when generating the hint string
                    // because it will also create a leak record.
                    return strBuf.append("<unknown>}").toString();
                }
            } catch (IllegalReferenceCountException e) {
                // Shouldn't really happen when used ByteBuf correctly,
                // but we just don't make toString() fail because of this.
                return strBuf.append("badRefCnt}").toString();
            }
        } else {
            offset = 0;
        }

        return ByteArrayHttpData.appendPreviews(strBuf, array, offset, previewLength)
                                .append('}').toString();
    }

    @Override
    public String toHintString() {
        return toString(true);
    }

    @Override
    public InputStream toInputStream() {
        if (array != null) {
            return new FastByteArrayInputStream(array);
        } else {
            return new ByteBufInputStream(buf.duplicate(), false);
        }
    }

    @Override
    public boolean isEndOfStream() {
        return (flags & FLAG_END_OF_STREAM) != 0;
    }

    @Override
    public ByteBufHttpData withEndOfStream(boolean endOfStream) {
        if (isEndOfStream() == endOfStream) {
            return this;
        }

        int newFlags = flags & ~FLAG_END_OF_STREAM;
        if (endOfStream) {
            newFlags |= FLAG_END_OF_STREAM;
        }

        return new ByteBufHttpData(buf, newFlags, array);
    }

    @Override
    public boolean isPooled() {
        return (flags & FLAG_POOLED) != 0;
    }

    @Override
    public ByteBuf byteBuf(ByteBufAccessMode mode) {
        switch (requireNonNull(mode, "mode")) {
            case DUPLICATE:
                return buf.duplicate();
            case RETAINED_DUPLICATE:
                return buf.retainedDuplicate();
            case FOR_IO:
                if (buf.isDirect()) {
                    return buf.retainedDuplicate();
                }

                final ByteBuf copy = newDirectByteBuf();
                copy.writeBytes(buf, buf.readerIndex(), buf.readableBytes());
                return copy;
        }

        throw new Error(); // Never reaches here.
    }

    @Override
    public ByteBuf byteBuf(int offset, int length, ByteBufAccessMode mode) {
        final int startIndex = buf.readerIndex() + offset;
        switch (requireNonNull(mode, "mode")) {
            case DUPLICATE:
                return buf.slice(startIndex, length);
            case RETAINED_DUPLICATE:
                return buf.retainedSlice(startIndex, length);
            case FOR_IO:
                if (buf.isDirect()) {
                    return buf.retainedSlice(startIndex, length);
                }

                final ByteBuf copy = newDirectByteBuf(length);
                copy.writeBytes(buf, startIndex, length);
                return copy;
        }

        throw new Error(); // Never reaches here.
    }

    private ByteBuf newDirectByteBuf() {
        return newDirectByteBuf(buf.readableBytes());
    }

    private static ByteBuf newDirectByteBuf(int length) {
        return PooledByteBufAllocator.DEFAULT.directBuffer(length);
    }

    @Override
    public void touch(@Nullable Object hint) {
        if (isPooled()) {
            buf.touch(firstNonNull(hint, this));
        }
    }

    @Override
    public void close() {
        // This is not thread safe, but an attempt to close one instance from multiple threads would fail
        // with an IllegalReferenceCountException anyway.
        if ((flags & (FLAG_POOLED | FLAG_CLOSED)) == FLAG_POOLED) {
            flags |= FLAG_CLOSED;
            buf.release();
        }
    }

    @Override
    public int hashCode() {
        // Calculate the hash code from the first 32 bytes.
        int hash = 0;
        final int bufStart = buf.readerIndex();
        final int bufEnd = bufStart + Math.min(32, buf.readableBytes());
        for (int i = bufStart; i < bufEnd; i++) {
            hash = hash * 31 + buf.getByte(i);
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof HttpData)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final HttpData that = (HttpData) obj;
        if (buf.readableBytes() != that.length()) {
            return false;
        }

        return ByteBufUtil.equals(buf, that.byteBuf());
    }
}
