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

import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * Adapted version of xxHash implementation from https://github.com/Cyan4973/xxHash.
 * This implementation provides endian-independant hash values, but it's slower on big-endian platforms.
 */
// Forked from https://github.com/OpenHFT/Zero-Allocation-Hashing/blob/ea/src/main/java/net/openhft/hashing/XxHash.java
public class XxHash {
    // Primes if treated as unsigned
    private static final long P1 = -7046029288634856825L;
    private static final long P2 = -4417276706812531889L;
    private static final long P3 = 1609587929392839161L;
    private static final long P4 = -8796714831421723037L;
    private static final long P5 = 2870177450012600261L;

    static <T> long xxHash64(long seed, T input, Access<T> access, long off, long length) {
        long hash;
        long remaining = length;

        if (remaining >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;

            do {
                v1 += access.i64(input, off) * P2;
                v1 = Long.rotateLeft(v1, 31);
                v1 *= P1;

                v2 += access.i64(input, off + 8) * P2;
                v2 = Long.rotateLeft(v2, 31);
                v2 *= P1;

                v3 += access.i64(input, off + 16) * P2;
                v3 = Long.rotateLeft(v3, 31);
                v3 *= P1;

                v4 += access.i64(input, off + 24) * P2;
                v4 = Long.rotateLeft(v4, 31);
                v4 *= P1;

                off += 32;
                remaining -= 32;
            } while (remaining >= 32);

            hash = Long.rotateLeft(v1, 1)
                   + Long.rotateLeft(v2, 7)
                   + Long.rotateLeft(v3, 12)
                   + Long.rotateLeft(v4, 18);

            v1 *= P2;
            v1 = Long.rotateLeft(v1, 31);
            v1 *= P1;
            hash ^= v1;
            hash = hash * P1 + P4;

            v2 *= P2;
            v2 = Long.rotateLeft(v2, 31);
            v2 *= P1;
            hash ^= v2;
            hash = hash * P1 + P4;

            v3 *= P2;
            v3 = Long.rotateLeft(v3, 31);
            v3 *= P1;
            hash ^= v3;
            hash = hash * P1 + P4;

            v4 *= P2;
            v4 = Long.rotateLeft(v4, 31);
            v4 *= P1;
            hash ^= v4;
            hash = hash * P1 + P4;
        } else {
            hash = seed + P5;
        }

        hash += length;

        while (remaining >= 8) {
            long k1 = access.i64(input, off);
            k1 *= P2;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= P1;
            hash ^= k1;
            hash = Long.rotateLeft(hash, 27) * P1 + P4;
            off += 8;
            remaining -= 8;
        }

        if (remaining >= 4) {
            hash ^= access.u32(input, off) * P1;
            hash = Long.rotateLeft(hash, 23) * P2 + P3;
            off += 4;
            remaining -= 4;
        }

        while (remaining != 0) {
            hash ^= access.u8(input, off) * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
            --remaining;
            ++off;
        }

        return finalize(hash);
    }

    private static long finalize(long hash) {
        hash ^= hash >>> 33;
        hash *= P2;
        hash ^= hash >>> 29;
        hash *= P3;
        hash ^= hash >>> 32;
        return hash;
    }

    static LongHashFunction asLongHashFunctionWithoutSeed() {
        return AsLongHashFunction.SEEDLESS_INSTANCE;
    }

    private static class AsLongHashFunction extends LongHashFunction {
        private static final long serialVersionUID = 0L;
        static final AsLongHashFunction SEEDLESS_INSTANCE = new AsLongHashFunction();
        private static final long VOID_HASH = XxHash.finalize(P5);

        private Object readResolve() {
            return SEEDLESS_INSTANCE;
        }

        public long seed() {
            return 0L;
        }

        @Override
        public long hashLong(long input) {
            input = Primitives.nativeToLittleEndian(input);
            long hash = seed() + P5 + 8;
            input *= P2;
            input = Long.rotateLeft(input, 31);
            input *= P1;
            hash ^= input;
            hash = Long.rotateLeft(hash, 27) * P1 + P4;
            return XxHash.finalize(hash);
        }

        @Override
        public long hashInt(int input) {
            input = Primitives.nativeToLittleEndian(input);
            long hash = seed() + P5 + 4;
            hash ^= Primitives.unsignedInt(input) * P1;
            hash = Long.rotateLeft(hash, 23) * P2 + P3;
            return XxHash.finalize(hash);
        }

        @Override
        public long hashShort(short input) {
            input = Primitives.nativeToLittleEndian(input);
            long hash = seed() + P5 + 2;
            hash ^= Primitives.unsignedByte(input) * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
            hash ^= Primitives.unsignedByte(input >> 8) * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
            return XxHash.finalize(hash);
        }

        @Override
        public long hashChar(char input) {
            return hashShort((short) input);
        }

        @Override
        public long hashByte(byte input) {
            long hash = seed() + P5 + 1;
            hash ^= Primitives.unsignedByte(input) * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
            return XxHash.finalize(hash);
        }

        @Override
        public long hashVoid() {
            return VOID_HASH;
        }

        @Override
        public <T> long hash(T input, Access<T> access, long off, long len) {
            long seed = seed();
            return XxHash.xxHash64(seed, input, access.byteOrder(input, LITTLE_ENDIAN), off, len);
        }
    }

    static LongHashFunction asLongHashFunctionWithSeed(long seed) {
        return new AsLongHashFunctionSeeded(seed);
    }

    private static class AsLongHashFunctionSeeded extends AsLongHashFunction {
        private static final long serialVersionUID = 0L;

        private final long seed;
        private final transient long voidHash;

        private AsLongHashFunctionSeeded(long seed) {
            this.seed = seed;
            voidHash = XxHash.finalize(seed + P5);
        }

        @Override
        public long seed() {
            return seed;
        }

        @Override
        public long hashVoid() {
            return voidHash;
        }
    }
}