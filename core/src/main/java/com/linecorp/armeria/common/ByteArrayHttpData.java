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
package com.linecorp.armeria.common;

import static com.linecorp.armeria.internal.common.ByteArrayUtil.appendPreviews;
import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.internal.EmptyArrays;
import it.unimi.dsi.fastutil.io.FastByteArrayInputStream;

/**
 * A {@code byte[]}-based {@link HttpData}.
 */
final class ByteArrayHttpData implements HttpData {

    static final ByteArrayHttpData EMPTY = new ByteArrayHttpData(EmptyArrays.EMPTY_BYTES, false);
    static final ByteArrayHttpData EMPTY_EOS = new ByteArrayHttpData(EmptyArrays.EMPTY_BYTES, true);

    private final byte[] array;
    private final boolean endOfStream;

    ByteArrayHttpData(byte[] array) {
        this(array, false);
    }

    private ByteArrayHttpData(byte[] array, boolean endOfStream) {
        this.array = array;
        this.endOfStream = endOfStream;
    }

    @Override
    public byte[] array() {
        return array;
    }

    @Override
    public int length() {
        return array.length;
    }

    @Override
    public String toString(Charset charset) {
        requireNonNull(charset, "charset");
        return new String(array, charset);
    }

    @Override
    public String toString() {
        if (array.length == 0) {
            return isEndOfStream() ? "{0B, EOS}" : "{0B}";
        }

        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append('{').append(array.length);

            if (isEndOfStream()) {
                buf.append("B, EOS, ");
            } else {
                buf.append("B, ");
            }

            return appendPreviews(buf, array, 0, Math.min(16, array.length)).append('}')
                                                                            .toString();
        }
    }

    @Override
    public InputStream toInputStream() {
        return new FastByteArrayInputStream(array);
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @Override
    public ByteArrayHttpData withEndOfStream(boolean endOfStream) {
        if (isEndOfStream() == endOfStream) {
            return this;
        }

        if (isEmpty()) {
            return endOfStream ? EMPTY_EOS : EMPTY;
        }

        return new ByteArrayHttpData(array, endOfStream);
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
    public ByteBuf byteBuf(int offset, int length, ByteBufAccessMode mode) {
        requireNonNull(mode, "mode");
        if (length == 0) {
            return Unpooled.EMPTY_BUFFER;
        }

        if (mode != ByteBufAccessMode.FOR_IO) {
            return Unpooled.wrappedBuffer(array, offset, length);
        } else {
            final ByteBuf copy = newDirectByteBuf(length);
            copy.writeBytes(array, offset, length);
            return copy;
        }
    }

    private static ByteBuf newDirectByteBuf(int length) {
        return PooledByteBufAllocator.DEFAULT.directBuffer(length);
    }

    @Override
    public void close() {}

    @Override
    public int hashCode() {
        // Calculate the hash code from the first 32 bytes.
        final int end = Math.min(32, length());

        int hash = 0;
        for (int i = 0; i < end; i++) {
            hash = hash * 31 + array[i];
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
        if (length() != that.length()) {
            return false;
        }

        return Arrays.equals(array, that.array());
    }
}
