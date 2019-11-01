/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.internal;

import java.util.UUID;
import java.util.function.Supplier;

import io.netty.util.internal.ThreadLocalRandom;

public final class UuidUtil {

    private static final byte[] HEXDIGITS = { '0', '1', '2', '3', '4', '5', '6', '7',
                                              '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    /**
     * Returns the {@link Supplier} that generates a random {@link UUID}. Similar to {@link UUID#randomUUID()}
     * except that it uses a cryptographically insecure random for higher performance.
     */
    public static Supplier<UUID> randomGenerator() {
        return RandomUuidGenerator.INSTANCE;
    }

    /**
     * Returns a newly generated random {@link UUID}.
     */
    public static UUID random() {
        return randomGenerator().get();
    }

    /**
     * Returns the first 8 hexadigits of the specified {@link UUID}.
     */
    public static String abbreviate(UUID uuid) {
        int value = (int) (uuid.getMostSignificantBits() >>> 32L);
        final byte[] bytes = new byte[8];
        for (int i = 0; i < bytes.length;) {
            bytes[i++] = HEXDIGITS[value >>> 28];
            bytes[i++] = HEXDIGITS[(value >>> 24) & 0xF];
            value <<= 8;
        }

        //noinspection deprecation
        return new String(bytes, 0);
    }

    private UuidUtil() {}

    private static final class RandomUuidGenerator implements Supplier<UUID> {

        static final RandomUuidGenerator INSTANCE = new RandomUuidGenerator();

        @Override
        public UUID get() {
            final ThreadLocalRandom rand = ThreadLocalRandom.current();
            long msb = rand.nextLong();
            long lsb = rand.nextLong();
            // Clear and set the version 4.
            msb &= 0xFFFFFFFFFFFF0FFFL;
            msb |= 0x0000000000004000L;
            // Clear and set IETF variant.
            lsb &= 0x3FFFFFFFFFFFFFFFL;
            lsb |= 0x8000000000000000L;
            return new UUID(msb, lsb);
        }
    }
}
