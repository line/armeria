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
package com.linecorp.armeria.common.grpc.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

class Base64DecoderTest {

    private static final byte[][] EMPTY_BYTES = new byte[0][0];

    @Test
    void decodeMultipleEncodedBytes() {
        final String str = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz1234567890";
        final byte[] bytes = str.getBytes();
        final List<byte[]> fragments = fragmentRandomly(bytes);
        final int half = fragments.size() / 2;
        final ByteBuf first =
                Unpooled.wrappedBuffer(fragments.subList(0, half).toArray(EMPTY_BYTES));
        final ByteBuf second =
                Unpooled.wrappedBuffer(fragments.subList(half, fragments.size()).toArray(EMPTY_BYTES));
        final Base64Decoder base64Decoder = new Base64Decoder(PooledByteBufAllocator.DEFAULT);
        final ByteBuf decodedFirst = base64Decoder.decode(first);
        final ByteBuf decodedSecond = base64Decoder.decode(second);
        assertThat(Unpooled.wrappedBuffer(decodedFirst, decodedSecond).toString(Charset.defaultCharset()))
                .isEqualTo(str);
    }

    private static List<byte[]> fragmentRandomly(byte[] bytes) {
        final List<byte[]> fragments = new ArrayList<>();
        for (int i = 0; i < bytes.length;) {
            final int to = Math.min(bytes.length,
                                    new Random().nextInt(5) + 1 + i); // One byte is selected at least.
            final byte[] encoded = Base64.getEncoder().encode(Arrays.copyOfRange(bytes, i, to));
            fragments.add(encoded);
            i = to;
        }
        return fragments;
    }
}
