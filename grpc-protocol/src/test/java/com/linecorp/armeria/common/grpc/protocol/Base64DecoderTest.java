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

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;

class Base64DecoderTest {

    private static final ByteBuf[] EMPTY_BYTE_BUF = new ByteBuf[0];

    @Test
    void decodeConcatenatedBufsWithPadding() {
        final String str = "abcd"; // YWJjZA==
        final ByteBuf buf = Unpooled.wrappedBuffer(str.getBytes());
        final ByteBuf encoded1 = Base64.encode(buf);
        buf.readerIndex(0);
        final ByteBuf encoded2 = Base64.encode(buf);
        final ByteBuf concatenated = Unpooled.wrappedBuffer(encoded1, encoded2); // YWJjZA==YWJjZA==
        final Base64Decoder base64Decoder = new Base64Decoder(PooledByteBufAllocator.DEFAULT);
        final ByteBuf decoded = base64Decoder.decode(concatenated);
        assertThat(decoded.toString(Charset.defaultCharset())).isEqualTo("abcdabcd");
        decoded.release();
    }

    @Test
    void decodeFragments() {
        final String str = "abcd"; // YWJjZA==
        final ByteBuf buf = Unpooled.wrappedBuffer(str.getBytes());
        final ByteBuf encoded1 = Base64.encode(buf);
        buf.readerIndex(0);
        final ByteBuf encoded2 = Base64.encode(buf);
        final ByteBuf concatenated = Unpooled.wrappedBuffer(encoded1, encoded2); // YWJjZA==YWJjZA==
        final Base64Decoder base64Decoder = new Base64Decoder(PooledByteBufAllocator.DEFAULT);
        final ByteBuf decodedFirst = base64Decoder.decode(concatenated.retainedSlice(0, 5)); // YWJjZ
        final ByteBuf decodedSecond = base64Decoder.decode(concatenated.retainedSlice(5, 11)); // A==YWJjZA==
        assertThat(Unpooled.wrappedBuffer(decodedFirst, decodedSecond).toString(Charset.defaultCharset()))
                .isEqualTo("abcdabcd");
        concatenated.release();
        decodedFirst.release();
        decodedSecond.release();
    }
}
