/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common.util;

import java.nio.ByteOrder;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

// Forked from https://github.com/OpenHFT/Zero-Allocation-Hashing/blob/ea/src/main/java/net/openhft/hashing/CharSequenceAccess.java
public abstract class CharSequenceAccess extends Access<CharSequence> {

    static CharSequenceAccess charSequenceAccess(ByteOrder order) {
        return order == LITTLE_ENDIAN ?
               LittleEndianCharSequenceAccess.INSTANCE :
               BigEndianCharSequenceAccess.INSTANCE;
    }

    private static int ix(long offset) {
        return (int) (offset >> 1);
    }

    protected static long getLong(CharSequence input, long offset,
                                  int char0Off, int char1Off, int char2Off, int char3Off,
                                  int char4Off, int delta) {
        final int base = ix(offset);
        if (0 == ((int)offset & 1)) {
            final long char0 = input.charAt(base + char0Off);
            final long char1 = input.charAt(base + char1Off);
            final long char2 = input.charAt(base + char2Off);
            final long char3 = input.charAt(base + char3Off);
            return char0 | (char1 << 16) | (char2 << 32) | (char3 << 48);
        } else {
            final long char0 = input.charAt(base + char0Off + delta) >>> 8;
            final long char1 = input.charAt(base + char1Off + delta);
            final long char2 = input.charAt(base + char2Off + delta);
            final long char3 = input.charAt(base + char3Off + delta);
            final long char4 = input.charAt(base + char4Off);
            return char0 | (char1 << 8) | (char2 << 24) | (char3 << 40) | (char4 << 56);
        }
    }

    protected static long getUnsignedInt(CharSequence input, long offset,
                                         int char0Off, int char1Off, int char2Off, int delta) {
        final int base = ix(offset);
        if (0 == ((int)offset & 1)) {
            final long char0 = input.charAt(base + char0Off);
            final long char1 = input.charAt(base + char1Off);
            return char0 | (char1 << 16);
        } else {
            final long char0 = input.charAt(base + char0Off + delta) >>> 8;
            final long char1 = input.charAt(base + char1Off + delta);
            final long char2 = Primitives.unsignedByte(input.charAt(base + char2Off));
            return char0 | (char1 << 8) | (char2 << 24);
        }
    }

    protected static char getUnsignedShort(CharSequence input,
                                           long offset, int char1Off, int delta) {
        if (0 == ((int)offset & 1)) {
            return input.charAt(ix(offset));
        } else {
            final int base = ix(offset);
            final int char0 = input.charAt(base + delta) >>> 8;
            final int char1 = input.charAt(base + char1Off);
            return (char)(char0 | (char1 << 8));
        }
    }

    protected static int getUnsignedByte(CharSequence input, long offset, int shift) {
        return Primitives.unsignedByte(input.charAt(ix(offset)) >> shift);
    }

    private CharSequenceAccess() {}

    @Override
    public int getInt(CharSequence input, long offset) {
        return (int) getUnsignedInt(input, offset);
    }

    @Override
    public int getShort(CharSequence input, long offset) {
        return (int)(short)getUnsignedShort(input, offset);
    }

    @Override
    public int getByte(CharSequence input, long offset) {
        return (int) (byte) getUnsignedByte(input, offset);
    }

    private static class LittleEndianCharSequenceAccess extends CharSequenceAccess {
        private static final CharSequenceAccess INSTANCE = new LittleEndianCharSequenceAccess();
        private static final Access<CharSequence> INSTANCE_REVERSE = Access.newDefaultReverseAccess(INSTANCE);

        private LittleEndianCharSequenceAccess() {}

        @Override
        public long getLong(CharSequence input, long offset) {
            return getLong(input, offset, 0, 1, 2, 3, 4, 0);
        }

        @Override
        public long getUnsignedInt(CharSequence input, long offset) {
            return getUnsignedInt(input, offset, 0, 1, 2, 0);
        }

        @Override
        public int getUnsignedShort(CharSequence input, long offset) {
            return getUnsignedShort(input, offset, 1, 0);
        }

        @Override
        public int getUnsignedByte(CharSequence input, long offset) {
            return getUnsignedByte(input, offset, ((int) offset & 1) << 3);
        }

        @Override
        public ByteOrder byteOrder(CharSequence input) {
            return LITTLE_ENDIAN;
        }

        @Override
        protected Access<CharSequence> reverseAccess() {
            return INSTANCE_REVERSE;
        }
    }

    private static class BigEndianCharSequenceAccess extends CharSequenceAccess {
        private static final CharSequenceAccess INSTANCE = new BigEndianCharSequenceAccess();
        private static final Access<CharSequence> INSTANCE_REVERSE = Access.newDefaultReverseAccess(INSTANCE);

        private BigEndianCharSequenceAccess() {}

        @Override
        public long getLong(CharSequence input, long offset) {
            return getLong(input, offset, 3, 2, 1, 0, 0, 1);
        }

        @Override
        public long getUnsignedInt(CharSequence input, long offset) {
            return getUnsignedInt(input, offset, 1, 0, 0, 1);
        }

        @Override
        public int getUnsignedShort(CharSequence input, long offset) {
            return getUnsignedShort(input, offset, 0, 1);
        }

        @Override
        public int getUnsignedByte(CharSequence input, long offset) {
            return getUnsignedByte(input, offset, (((int) offset & 1) ^ 1) << 3);
        }

        @Override
        public ByteOrder byteOrder(CharSequence input) {
            return BIG_ENDIAN;
        }

        @Override
        protected Access<CharSequence> reverseAccess() {
            return INSTANCE_REVERSE;
        }
    }
}
