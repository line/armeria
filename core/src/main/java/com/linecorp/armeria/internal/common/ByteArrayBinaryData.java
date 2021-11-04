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
package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

import com.linecorp.armeria.common.BinaryData;
import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.internal.EmptyArrays;
import it.unimi.dsi.fastutil.io.FastByteArrayInputStream;

/**
 * A {@code byte[]}-based {@link BinaryData}.
 */
public final class ByteArrayBinaryData implements BinaryData {

    private static final BinaryData empty = new ByteArrayBinaryData(EmptyArrays.EMPTY_BYTES);

    public static BinaryData empty() {
        return empty;
    }

    private static final byte[] SAFE_OCTETS = new byte[256];

    static {
        final String safeOctetStr = "`~!@#$%^&*()-_=+\t[{]}\\|;:'\",<.>/?" +
                                    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < safeOctetStr.length(); i++) {
            SAFE_OCTETS[safeOctetStr.charAt(i)] = -1;
        }
    }

    private final byte[] array;

    /**
     * Creates a new instance.
     */
    public ByteArrayBinaryData(byte[] array) {
        this.array = requireNonNull(array, "array");
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
            return "{0B}";
        }
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append('{').append(array.length).append("B, ");

            return appendPreviews(buf, array, 0, Math.min(16, array.length))
                    .append('}').toString();
        }
    }

    static StringBuilder appendPreviews(StringBuilder buf, byte[] array, int offset, int previewLength) {
        // Append the hex preview if contains non-ASCII chars.
        final int endOffset = offset + previewLength;
        for (int i = offset; i < endOffset; i++) {
            if (SAFE_OCTETS[array[i] & 0xFF] == 0) {
                return buf.append("hex=").append(ByteBufUtil.hexDump(array, offset, previewLength));
            }
        }

        // Append the text preview otherwise.
        return buf.append("text=").append(new String(array, 0, offset, previewLength));
    }

    @Override
    public InputStream toInputStream() {
        return new FastByteArrayInputStream(array);
    }

    @Override
    public boolean isPooled() {
        return false;
    }

    @Override
    public ByteBuf byteBuf(ByteBufAccessMode mode) {
        requireNonNull(mode, "mode");
        if (isEmpty()) {
            return Unpooled.EMPTY_BUFFER;
        }

        if (mode != ByteBufAccessMode.FOR_IO) {
            return Unpooled.wrappedBuffer(array);
        } else {
            final ByteBuf copy = newDirectByteBuf();
            copy.writeBytes(array);
            return copy;
        }
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

    private ByteBuf newDirectByteBuf() {
        return newDirectByteBuf(length());
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
        if (!(obj instanceof ByteArrayBinaryData)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final ByteArrayBinaryData that = (ByteArrayBinaryData) obj;
        if (length() != that.length()) {
            return false;
        }

        return Arrays.equals(array, that.array());
    }
}
