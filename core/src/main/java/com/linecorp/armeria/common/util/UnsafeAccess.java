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

import static com.linecorp.armeria.common.util.Primitives.NATIVE_LITTLE_ENDIAN;
import static com.linecorp.armeria.common.util.Primitives.unsignedByte;
import static com.linecorp.armeria.common.util.Primitives.unsignedInt;
import static com.linecorp.armeria.common.util.Primitives.unsignedShort;

import java.lang.reflect.Field;
import java.nio.ByteOrder;
import sun.misc.Unsafe;

// Forked from https://github.com/OpenHFT/Zero-Allocation-Hashing/blob/ea/src/main/java/net/openhft/hashing/UnsafeAccess.java
public class UnsafeAccess extends Access<Object> {
    public static final UnsafeAccess INSTANCE;
    private static final Access<Object> INSTANCE_NON_NATIVE;

    // for test only
    static final UnsafeAccess OLD_INSTANCE = NATIVE_LITTLE_ENDIAN
                                             ? new OldUnsafeAccessLittleEndian()
                                             : new OldUnsafeAccessBigEndian();

    static final Unsafe UNSAFE;

    static final long BOOLEAN_BASE;
    static final long BYTE_BASE;
    static final long CHAR_BASE;
    static final long SHORT_BASE;
    static final long INT_BASE;
    static final long LONG_BASE;

    static final byte TRUE_BYTE_VALUE;
    static final byte FALSE_BYTE_VALUE;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);

            BOOLEAN_BASE = UNSAFE.arrayBaseOffset(boolean[].class);
            BYTE_BASE = UNSAFE.arrayBaseOffset(byte[].class);
            CHAR_BASE = UNSAFE.arrayBaseOffset(char[].class);
            SHORT_BASE = UNSAFE.arrayBaseOffset(short[].class);
            INT_BASE = UNSAFE.arrayBaseOffset(int[].class);
            LONG_BASE = UNSAFE.arrayBaseOffset(long[].class);

            TRUE_BYTE_VALUE = (byte)UNSAFE.getInt(new boolean[] {true, true, true, true},
                                                  BOOLEAN_BASE);
            FALSE_BYTE_VALUE = (byte)UNSAFE.getInt(new boolean[] {false, false, false, false},
                                                   BOOLEAN_BASE);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }

        boolean hasGetByte = true;
        try {
            UNSAFE.getByte(new byte[1], BYTE_BASE);
        } catch (final Throwable ignore) {
            // Unsafe in pre-Nougat Android does not have getByte(), fall back to workround
            hasGetByte = false;
        }

        INSTANCE = hasGetByte ? new UnsafeAccess() : OLD_INSTANCE;
        INSTANCE_NON_NATIVE = Access.newDefaultReverseAccess(INSTANCE);
    }

    private UnsafeAccess() {}

    @Override
    public long getLong(Object input, long offset) {
        return UNSAFE.getLong(input, offset);
    }

    @Override
    public long getUnsignedInt(Object input, long offset) {
        return unsignedInt(getInt(input, offset));
    }

    @Override
    public int getInt(Object input, long offset) {
        return UNSAFE.getInt(input, offset);
    }

    @Override
    public int getUnsignedShort(Object input, long offset) {
        return unsignedShort(getShort(input, offset));
    }

    @Override
    public int getShort(Object input, long offset) {
        return UNSAFE.getShort(input, offset);
    }

    @Override
    public int getUnsignedByte(Object input, long offset) {
        return unsignedByte(getByte(input, offset));
    }

    @Override
    public int getByte(Object input, long offset) {
        return UNSAFE.getByte(input, offset);
    }

    @Override
    public ByteOrder byteOrder(Object input) {
        return ByteOrder.nativeOrder();
    }

    @Override
    protected Access<Object> reverseAccess() {
        return INSTANCE_NON_NATIVE;
    }

    private static class OldUnsafeAccessLittleEndian extends UnsafeAccess {
        @Override
        public int getShort(final Object input, final long offset) {
            return UNSAFE.getInt(input, offset - 2) >> 16;
        }

        @Override
        public int getByte(final Object input, final long offset) {
            return UNSAFE.getInt(input, offset - 3) >> 24;
        }
    }

    private static class OldUnsafeAccessBigEndian extends UnsafeAccess {
        @Override
        public int getShort(final Object input, final long offset) {
            return (int)(short)UNSAFE.getInt(input, offset - 2);
        }

        @Override
        public int getByte(final Object input, final long offset) {
            return (int)(byte)UNSAFE.getInt(input, offset - 3);
        }
    }
}
