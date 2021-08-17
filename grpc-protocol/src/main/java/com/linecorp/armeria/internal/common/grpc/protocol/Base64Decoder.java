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
/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 * Written by Robert Harder and released to the public domain, as explained at
 * http://creativecommons.org/licenses/publicdomain
 */
package com.linecorp.armeria.internal.common.grpc.protocol;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ByteProcessor;

/**
 * A stateful Base64decoder. Unlike {@link io.netty.handler.codec.base64.Base64Decoder}, this decoder does
 * not end when it meets padding('='), but continues to decode until the end of the {@link ByteBuf} given by
 * {@link #decode(ByteBuf)}. If the {@link ByteBuf} does not have necessary 4 bytes to decode as 3 bytes,
 * it stores the remained bytes and prepend them to the next {@link #decode(ByteBuf)} and decode together.
 */
public final class Base64Decoder implements ByteProcessor {

    // Forked from https://github.com/netty/netty/blob/netty-4.1.51.Final/codec
    // /src/main/java/io/netty/handler/codec/base64/Base64.java

    private static final byte EQUALS_SIGN_ENC = -1; // Indicates equals sign in encoding

    private static final byte[] DECODABET = {
            -9, -9, -9, -9, -9, -9,
            -9, -9, -9, // Decimal  0 -  8
            -5, -5, // Whitespace: Tab and Linefeed
            -9, -9, // Decimal 11 - 12
            -5, // Whitespace: Carriage Return
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, // Decimal 14 - 26
            -9, -9, -9, -9, -9, // Decimal 27 - 31
            -5, // Whitespace: Space
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, // Decimal 33 - 42
            62, // Plus sign at decimal 43
            -9, -9, -9, // Decimal 44 - 46
            63, // Slash at decimal 47
            52, 53, 54, 55, 56, 57, 58, 59, 60, 61, // Numbers zero through nine
            -9, -9, -9, // Decimal 58 - 60
            -1, // Equals sign at decimal 61
            -9, -9, -9, // Decimal 62 - 64
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, // Letters 'A' through 'N'
            14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, // Letters 'O' through 'Z'
            -9, -9, -9, -9, -9, -9, // Decimal 91 - 96
            26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, // Letters 'a' through 'm'
            39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, // Letters 'n' through 'z'
            -9, -9, -9, -9, -9, // Decimal 123 - 127
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,     // Decimal 128 - 140
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,     // Decimal 141 - 153
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,     // Decimal 154 - 166
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,     // Decimal 167 - 179
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,     // Decimal 180 - 192
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,     // Decimal 193 - 205
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,     // Decimal 206 - 218
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,     // Decimal 219 - 231
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9,     // Decimal 232 - 244
            -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9              // Decimal 245 - 255
    };

    private final ByteBufAllocator allocator;
    @Nullable
    private ByteBuf dest;
    private final byte[] last3 = new byte[3]; // The 4th byte is stored in a local variable.
    private int pos;

    public Base64Decoder(ByteBufAllocator allocator) {
        this.allocator = allocator;
    }

    public ByteBuf decode(ByteBuf src) {
        final ByteBuf dest = allocator.buffer(decodedBufferSize(src.readableBytes()));
        this.dest = dest;
        boolean success = false;
        try {
            src.forEachByte(this);
            success = true;
            return dest;
        } finally {
            if (!success) {
                dest.release();
            }
            src.release();
        }
    }

    private int decodedBufferSize(int len) {
        return (len + pos) / 4 * 3;
    }

    @Override
    public boolean process(byte value) throws Exception {
        final byte decodedByte = DECODABET[value & 0xFF];
        if (decodedByte < EQUALS_SIGN_ENC) {
            throw new IllegalArgumentException(
                    "invalid Base64 input character: " + (short) (value & 0xFF) + " (decimal)");
        }

        // Equals sign or better
        if (pos < 3) {
            if (pos < 2 && decodedByte == EQUALS_SIGN_ENC) {
                throw new IllegalArgumentException(
                        "invalid padding position: " + pos + " (expected: 2 or 3)");
            }

            // Needs more bytes.
            last3[pos++] = decodedByte;
            return true;
        }

        // Now we have 4 bytes that are decoded into 1-3 bytes.
        pos = 0;

        final byte b0 = last3[0];
        final byte b1 = last3[1];
        final byte b2 = last3[2];

        final ByteBuf dest = this.dest;
        assert dest != null;

        final byte b3 = decodedByte;
        if (b2 == EQUALS_SIGN_ENC) {
            if (b3 != EQUALS_SIGN_ENC) {
                throw new IllegalArgumentException(
                        "a non padding character can't follow to a padding. character: " + b3);
            }

            // Example: Dk==
            dest.writeByte((b0 & 0xff) << 2 | (b1 & 0xff) >>> 4);
            return true;
        }

        if (b3 == EQUALS_SIGN_ENC) {
            // Example: DkL=
            // Packing bytes into a short to reduce bound and reference count checking.
            // The decodabet bytes are meant to straddle byte boundaries and so we must carefully mask out
            // the bits we care about.
            dest.writeShort(((b0 & 0x3f) << 2 | (b1 & 0xf0) >> 4) << 8 |
                            (b1 & 0xf) << 4 | (b2 & 0xfc) >>> 2);
            return true;
        }

        // Example: DkLE
        dest.writeMedium((b0 & 0x3f) << 18 |
                         (b1 & 0xff) << 12 |
                         (b2 & 0xff) << 6 |
                         b3 & 0xff);
        return true;
    }
}
