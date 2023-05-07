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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * Strategy of reading bytes, defines the abstraction of {@code T} class instances as ordered byte
 * sequence. All {@code getXXX(input, offset)} should be consistent to each other in terms of
 * <i>ordered byte sequence</i> each {@code T} instance represents. For example, if some {@code
 * Access} implementation returns {@link ByteOrder#LITTLE_ENDIAN} on {@link #byteOrder(Object)
 * byteOrder(input)} call, the following expressions should always have the same value:
 * <ul>
 *     <li>{@code getLong(input, 0)}</li>
 *     <li>{@code getUnsignedInt(input, 0) | (getUnsignedInt(input, 4) << 32)}</li>
 *     <li><pre>{@code getUnsignedInt(input, 0) |
 *    ((long) getUnsignedShort(input, 4) << 32) |
 *    ((long) getUnsignedByte(input, 6) << 48) |
 *    ((long) getUnsignedByte(input, 7) << 56)}</pre></li>
 *   <li>And so on</li>
 * </ul>
 *
 * <p>{@code getXXX(input, offset)} methods could throw unchecked exceptions when requested bytes
 * range is outside of the bounds of the byte sequence, represented by the given {@code input}.
 * However, they could omit checks for better performance.
 *
 * <p>Only {@link #getByte(Object, long)} and {@link #byteOrder(Object)} methods are abstract in
 * this class, so implementing them is sufficient for valid {@code Access} instance, but for
 * efficiency your should override methods used by target {@link LongHashFunction} implementation.
 *
 * <p>{@code Access} API is designed for inputs, that actually represent byte sequences that lay
 * continuously in memory. Theoretically {@code Access} strategy could be implemented for
 * non-continuous byte sequences, or abstractions which aren't actually present in memory as they
 * are accessed, but this should be awkward, and hashing using such {@code Access} is expected to
 * be slow.
 *
 * @param <T> the type of the object to access
 * @see LongHashFunction#hash(Object, Access, long, long)
 */
// Forked from https://github.com/OpenHFT/Zero-Allocation-Hashing/blob/ea/src/main/java/net/openhft/hashing/Access.java
public abstract class Access<T> {

    /**
     * Returns the {@code Access} delegating {@code getXXX(input, offset)} methods to {@code
     * sun.misc.Unsafe.getXXX(input, offset)}.
     *
     * <p>Usage example: <pre>{@code
     * class Pair {
     *     long first, second;
     *
     *     static final long pairDataOffset =
     *         theUnsafe.objectFieldOffset(Pair.class.getDeclaredField("first"));
     *
     *     static long hashPair(Pair pair, LongHashFunction hashFunction) {
     *         return hashFunction.hash(pair, Access.unsafe(), pairDataOffset, 16L);
     *     }
     * }}</pre>
     *
     * <p>{@code null} is a valid input, on accepting {@code null} {@code Unsafe} just interprets
     * the given offset as a wild memory address. Note that for hashing memory by address there is
     * a shortcut {@link LongHashFunction#hashMemory(long, long) hashMemory(address, len)} method.
     *
     * @param <T> the type of objects to access
     * @return the unsafe memory {@code Access}
     */
    @SuppressWarnings("unchecked")
    public static <T> Access<T> unsafe() {
        return (Access<T>) UnsafeAccess.INSTANCE;
    }

    /**
     * Returns the {@code Access} to any {@link ByteBuffer}. This {@code Access} isn't useful in
     * the user code, because methods {@link LongHashFunction#hashBytes(ByteBuffer)} and
     * {@link LongHashFunction#hashBytes(ByteBuffer, int, int)} exist. This {@code Access} could be
     * used in new {@link LongHashFunction} implementations.
     *
     * @return the {@code Access} to {@link ByteBuffer}s
     */
    public static Access<ByteBuffer> toByteBuffer() {
        return ByteBufferAccess.INSTANCE;
    }

    /**
     * Returns the {@code Access} to {@link CharSequence}s backed by {@linkplain
     * ByteOrder#nativeOrder() native} {@code char} reads, typically from {@code char[]} array.
     *
     * <p>Usage example:<pre>{@code
     * static long hashStringBuffer(StringBuffer buffer, LongHashFunction hashFunction) {
     *     return hashFunction.hash(buffer, Access.toNativeCharSequence(),
     *         // * 2L because length is passed in bytes, not chars
     *         0L, buffer.length() * 2L);
     * }}</pre>
     *
     * <p>This method is a shortcut for {@code Access.toCharSequence(ByteOrder.nativeOrder())}.
     *
     * @param <T> the {@code CharSequence} subtype (backed by native {@code char reads}) to access
     * @return the {@code Access} to {@link CharSequence}s backed by native {@code char} reads
     * @see #toCharSequence(ByteOrder)
     */
    @SuppressWarnings("unchecked")
    public static <T extends CharSequence> Access<T> toNativeCharSequence() {
        return (Access<T>) CharSequenceAccess.nativeCharSequenceAccess();
    }

    /**
     * Returns the {@code Access} to {@link CharSequence}s backed by {@code char} reads made in
     * the specified byte order.
     *
     * <p>Usage example:<pre>{@code
     * static long hashCharBuffer(CharBuffer buffer, LongHashFunction hashFunction) {
     *     return hashFunction.hash(buffer, Access.toCharSequence(buffer.order()),
     *         // * 2L because length is passed in bytes, not chars
     *         0L, buffer.length() * 2L);
     * }}</pre>
     *
     * @param backingOrder the byte order of {@code char} reads backing
     * {@code CharSequences} to access
     * @return the {@code Access} to {@link CharSequence}s backed by {@code char} reads made in
     * the specified byte order
     * @param <T> the {@code CharSequence} subtype to access
     * @see #toNativeCharSequence()
     */
    @SuppressWarnings("unchecked")
    public static <T extends CharSequence> Access<T> toCharSequence(ByteOrder backingOrder) {
        return (Access<T>) CharSequenceAccess.charSequenceAccess(backingOrder);
    }

    /**
     * Constructor for use in subclasses.
     */
    protected Access() {}

    /**
     * Reads {@code [offset, offset + 7]} bytes of the byte sequence represented by the given
     * {@code input} as a single {@code long} value.
     *
     * @param input the object to access
     * @param offset offset to the first byte to read within the byte sequence represented
     * by the given object
     * @return eight bytes as a {@code long} value, in {@linkplain #byteOrder(Object) the expected
     * order}
     */
    public long getLong(T input, long offset) {
        if (byteOrder(input) == LITTLE_ENDIAN) {
            return getUnsignedInt(input, offset) | (getUnsignedInt(input, offset + 4L) << 32);
        } else {
            return getUnsignedInt(input, offset + 4L) | (getUnsignedInt(input, offset) << 32);
        }
    }

    /**
     * Shortcut for {@code getInt(input, offset) & 0xFFFFFFFFL}. Could be implemented more
     * efficiently.
     *
     * @param input the object to access
     * @param offset offset to the first byte to read within the byte sequence represented
     * by the given object
     * @return four bytes as an unsigned int value, in {@linkplain #byteOrder(Object) the expected
     * order}
     */
    public long getUnsignedInt(T input, long offset) {
        return ((long) getInt(input, offset)) & 0xFFFFFFFFL;
    }

    /**
     * Reads {@code [offset, offset + 3]} bytes of the byte sequence represented by the given
     * {@code input} as a single {@code int} value.
     *
     * @param input the object to access
     * @param offset offset to the first byte to read within the byte sequence represented
     * by the given object
     * @return four bytes as an {@code int} value, in {@linkplain #byteOrder(Object) the expected
     * order}
     */
    public int getInt(T input, long offset) {
        if (byteOrder(input) == LITTLE_ENDIAN) {
            return getUnsignedShort(input, offset) | (getUnsignedShort(input, offset + 2L) << 16);
        } else {
            return getUnsignedShort(input, offset + 2L) | (getUnsignedShort(input, offset) << 16);
        }
    }

    /**
     * Shortcut for {@code getShort(input, offset) & 0xFFFF}. Could be implemented more
     * efficiently.
     *
     * @param input the object to access
     * @param offset offset to the first byte to read within the byte sequence represented
     * by the given object
     * @return two bytes as an unsigned short value, in {@linkplain #byteOrder(Object) the expected
     * order}
     */
    public int getUnsignedShort(T input, long offset) {
        if (byteOrder(input) == LITTLE_ENDIAN) {
            return getUnsignedByte(input, offset) | (getUnsignedByte(input, offset + 1L) << 8);
        } else {
            return getUnsignedByte(input, offset + 1L) | (getUnsignedByte(input, offset) << 8);
        }
    }

    /**
     * Reads {@code [offset, offset + 1]} bytes of the byte sequence represented by the given
     * {@code input} as a single {@code short} value, returned widened to {@code int}.
     *
     * @param input the object to access
     * @param offset offset to the first byte to read within the byte sequence represented
     * by the given object
     * @return two bytes as a {@code short} value, in {@linkplain #byteOrder(Object) the expected
     * order}, widened to {@code int}
     */
    public int getShort(T input, long offset) {
        return (int) (short) getUnsignedShort(input, offset);
    }

    /**
     * Shortcut for {@code getByte(input, offset) & 0xFF}. Could be implemented more efficiently.
     *
     * @param input the object to access
     * @param offset offset to the byte to read within the byte sequence represented
     * by the given object
     * @return a byte by the given {@code offset}, interpreted as unsigned
     */
    public int getUnsignedByte(T input, long offset) {
        return getByte(input, offset) & 0xFF;
    }

    /**
     * Reads a single byte at the given {@code offset} in the byte sequence represented by the given
     * {@code input}, returned widened to {@code int}.
     *
     * @param input the object to access
     * @param offset offset to the byte to read within the byte sequence represented
     * by the given object
     * @return a byte by the given {@code offset}, widened to {@code int}
     */
    public abstract int getByte(T input, long offset);

    // short names
    public long i64(final T input, final long offset) { return getLong(input, offset); }
    public long u32(final T input, final long offset) { return getUnsignedInt(input, offset); }
    public  int i32(final T input, final long offset) { return getInt(input, offset); }
    public  int u16(final T input, final long offset) { return getUnsignedShort(input, offset); }
    public  int i16(final T input, final long offset) { return getShort(input, offset); }
    public  int  u8(final T input, final long offset) { return getUnsignedByte(input, offset); }
    public  int  i8(final T input, final long offset) { return getByte(input, offset); }

    /**
     * The byte order in which all multi-byte {@code getXXX()} reads from the given {@code input}
     * are performed.
     *
     * @param input the accessed object
     * @return the byte order of all multi-byte reads from the given {@code input}
     */
    public abstract ByteOrder byteOrder(T input);

    /**
     * Get {@code this} or the reversed access object for reading the input as fixed
     * byte order of {@code byteOrder}.
     *
     * @param input the accessed object
     * @param byteOrder the byte order to be used for reading the {@code input}
     * @return a {@code Access} object which will read the {@code input} with the
     * byte order of {@code byteOrder}.
     */
    public Access<T> byteOrder(final T input, final ByteOrder byteOrder) {
        return byteOrder(input) == byteOrder ? this : reverseAccess();
    }

    /**
     * Get the {@code Access} object with a different byte order. This method should
     * always return a fixed reference.
     */
    protected abstract Access<T> reverseAccess();

    /**
     * Get or create the reverse byte order {@code Access} object for {@code access}.
     */
    static <T> Access<T> newDefaultReverseAccess(final Access<T> access) {
        return access instanceof ReverseAccess
               ? access.reverseAccess()
               : new ReverseAccess<T>(access);
    }

    /**
     * The default reverse byte order delegating {@code Access} class.
     */
    private static class ReverseAccess<T> extends Access<T> {
        final Access<T> access;
        private ReverseAccess(final Access<T> access) {
            this.access = access;
        }
        @Override
        public long getLong(final T input, final long offset) {
            return Long.reverseBytes(access.getLong(input, offset));
        }
        @Override
        public long getUnsignedInt(final T input, final long offset) {
            return Long.reverseBytes(access.getUnsignedInt(input, offset)) >>> 32;
        }
        @Override
        public int getInt(final T input, final long offset) {
            return Integer.reverseBytes(access.getInt(input, offset));
        }
        @Override
        public int getUnsignedShort(final T input, final long offset) {
            return Integer.reverseBytes(access.getUnsignedShort(input, offset)) >>> 16;
        }
        @Override
        public int getShort(final T input, final long offset) {
            return Integer.reverseBytes(access.getShort(input, offset)) >> 16;
        }
        @Override
        public int getUnsignedByte(final T input, final long offset) {
            return access.getUnsignedByte(input, offset);
        }
        @Override
        public int getByte(final T input, final long offset) {
            return access.getByte(input, offset);
        }
        @Override
        public ByteOrder byteOrder(final T input) {
            return LITTLE_ENDIAN == access.byteOrder(input) ? BIG_ENDIAN : LITTLE_ENDIAN;
        }
        @Override
        protected Access<T> reverseAccess() {
            return access;
        }
    }
}
