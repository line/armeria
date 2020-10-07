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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.internal.common.grpc.protocol.Base64Decoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

class Base64DecoderTest {

    @Test
    void lineBreakTabSpaceIllegal() {
        invalidCharacter("YWJj\nZGU=");
        invalidCharacter("YWJj\tZGU=");
        invalidCharacter("YWJj ZGU=");
    }

    @Test
    void invalidString() {
        invalidCharacter("A\u007f"); // 0x7f is invalid
        invalidCharacter("Wf2!"); // ! is invalid
        invalidCharacter("?"); // ? is invalid
        // invalid padding position
        invalidCharacter("A=BC");
        invalidCharacter("=ABC");
        invalidCharacter("AB=C");
    }

    private static void invalidCharacter(String invalid) {
        final Base64Decoder decoder = new Base64Decoder(PooledByteBufAllocator.DEFAULT);
        final ByteBuf buf = Unpooled.wrappedBuffer(invalid.getBytes());
        assertThatThrownBy(() -> decoder.decode(buf)).isExactlyInstanceOf(IllegalArgumentException.class);
        assertThat(buf.refCnt()).isZero();
    }

    @ParameterizedTest
    @ExpectedAndEncodedStringSource
    void decode(String expected, String encoded) {
        final Base64Decoder base64Decoder = new Base64Decoder(PooledByteBufAllocator.DEFAULT);
        final ByteBuf decoded = base64Decoder.decode(Unpooled.wrappedBuffer(encoded.getBytes()));
        assertThat(decoded.toString(Charset.defaultCharset())).isEqualTo(expected);
        decoded.release();
    }

    @ParameterizedTest
    @ExpectedAndEncodedStringSource
    void decodeConcatenatedBufs(String expected, String encoded) {
        final ByteBuf buf1 = Unpooled.wrappedBuffer(encoded.getBytes());
        final ByteBuf buf2 = buf1.retainedDuplicate();

        final Base64Decoder base64Decoder = new Base64Decoder(PooledByteBufAllocator.DEFAULT);
        final ByteBuf decoded = base64Decoder.decode(Unpooled.wrappedBuffer(buf1, buf2));
        assertThat(decoded.toString(Charset.defaultCharset())).isEqualTo(expected + expected);
        decoded.release();
    }

    @ParameterizedTest
    @ExpectedAndEncodedStringSource
    void decodeEachByteSeparately(String expected, String encoded) {
        final ByteBuf buf = Unpooled.wrappedBuffer(encoded.getBytes());
        final Base64Decoder base64Decoder = new Base64Decoder(PooledByteBufAllocator.DEFAULT);
        final List<ByteBuf> bufs = new ArrayList<>();
        while (buf.isReadable()) {
            bufs.add(base64Decoder.decode(buf.readRetainedSlice(1)));
        }
        buf.release();
        final ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(bufs.toArray(new ByteBuf[0]));
        assertThat(wrappedBuffer.toString(Charset.defaultCharset())).isEqualTo(expected);
        wrappedBuffer.release();
    }

    @CsvSource({
            "f,             Zg==",
            "fo,            Zm8=",
            "foo,           Zm9v",
            "foob,          Zm9vYg==",
            "fooba,         Zm9vYmE=",
            "foobar,        Zm9vYmFy",
            "abcde,         YWJjZGU=",
            "123456789,     MTIzNDU2Nzg5",
            "~!@#$%^&*()-_, fiFAIyQlXiYqKCktXw=="
    })
    @Retention(RetentionPolicy.RUNTIME)
    @interface ExpectedAndEncodedStringSource {}
}
