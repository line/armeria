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
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

class Base64DecoderTest {

    @ParameterizedTest
    @ArgumentsSource(EncodedStringProvider.class)
    void decode(String expected, String encoded) {
        final Base64Decoder base64Decoder = new Base64Decoder(PooledByteBufAllocator.DEFAULT);
        final ByteBuf decoded = base64Decoder.decode(Unpooled.wrappedBuffer(encoded.getBytes()));
        assertThat(decoded.toString(Charset.defaultCharset())).isEqualTo(expected);
        decoded.release();
    }

    @ParameterizedTest
    @ArgumentsSource(EncodedStringProvider.class)
    void decodeConcatenatedBufs(String expected, String encoded) {
        final ByteBuf buf1 = Unpooled.wrappedBuffer(encoded.getBytes());
        final ByteBuf buf2 = buf1.retainedDuplicate();

        final Base64Decoder base64Decoder = new Base64Decoder(PooledByteBufAllocator.DEFAULT);
        final ByteBuf decoded = base64Decoder.decode(Unpooled.wrappedBuffer(buf1, buf2));
        assertThat(decoded.toString(Charset.defaultCharset())).isEqualTo(expected + expected);
        decoded.release();
    }

    @ParameterizedTest
    @ArgumentsSource(EncodedStringProvider.class)
    void decodeEachByteSeparately(String expected, String encoded) {
        final ByteBuf buf = Unpooled.wrappedBuffer(encoded.getBytes());
        final Base64Decoder base64Decoder = new Base64Decoder(PooledByteBufAllocator.DEFAULT);
        final int readableBytes = buf.readableBytes();
        final List<ByteBuf> bufs = new ArrayList<>();
        for (int i = 0; i < readableBytes; i++) {
            bufs.add(base64Decoder.decode(buf.retainedSlice(buf.readerIndex(), 1)));
            buf.readerIndex(i + 1);
        }
        buf.release();
        final ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(bufs.toArray(new ByteBuf[0]));
        assertThat(wrappedBuffer.toString(Charset.defaultCharset())).isEqualTo(expected);
        wrappedBuffer.release();
    }

    private static final class EncodedStringProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(Arguments.of("abcde", "YWJjZGU="),
                             Arguments.of("123456789", "MTIzNDU2Nzg5"),
                             Arguments.of("~!@#$%^&*()-_", "fiFAIyQlXiYqKCktXw=="));
        }
    }
}
