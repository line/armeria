/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.Reader;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

class HttpDataTest {

    @Nested
    class BytesData {
        // Not immutable
        private byte[] payload;

        @BeforeEach
        void initPayload() {
            payload = new byte[] { 1, 2, 3, 4 };
        }

        @Test
        void wrap() {
            final HttpData data = HttpData.wrap(payload);
            payload[1] = 5;
            assertThat(payload).containsExactly(1, 5, 3, 4);
            assertThat(data.array()).containsExactly(1, 5, 3, 4);
        }

        @Test
        void wrapRange() {
            final HttpData data = HttpData.wrap(payload, 1, 2);
            payload[1] = 5;
            assertThat(payload).containsExactly(1, 5, 3, 4);
            assertThat(data.array()).containsExactly(5, 3);
        }

        @Test
        void wrapRangeNoOffset() {
            final HttpData data = HttpData.wrap(payload, 0, 2);
            payload[1] = 5;
            assertThat(payload).containsExactly(1, 5, 3, 4);
            assertThat(data.array()).containsExactly(1, 5);
        }

        @Test
        void wrapRangeFull() {
            final HttpData data = HttpData.wrap(payload, 0, 4);
            payload[1] = 5;
            assertThat(payload).containsExactly(1, 5, 3, 4);
            assertThat(data.array()).containsExactly(1, 5, 3, 4);
        }

        @Test
        void copyOf() {
            final HttpData data = HttpData.copyOf(payload);
            payload[1] = 5;
            assertThat(payload).containsExactly(1, 5, 3, 4);
            assertThat(data.array()).containsExactly(1, 2, 3, 4);
        }

        @Test
        void copyOfRange() {
            final HttpData data = HttpData.copyOf(payload, 1, 2);
            payload[1] = 5;
            assertThat(payload).containsExactly(1, 5, 3, 4);
            assertThat(data.array()).containsExactly(2, 3);
        }
    }

    @Nested
    class ByteBufData {
        // Not immutable
        private ByteBuf payload;

        @BeforeEach
        void initPayload() {
            payload = Unpooled.copiedBuffer(new byte[] { 1, 2, 3, 4 });
        }

        @Test
        void wrap() {
            final HttpData data = HttpData.wrap(payload);
            payload.setByte(1, 5);
            assertThat(ByteBufUtil.getBytes(payload)).containsExactly(1, 5, 3, 4);
            assertThat(data.array()).containsExactly(1, 5, 3, 4);
        }

        @Test
        void copyOf() {
            final HttpData data = HttpData.copyOf(payload);
            payload.setByte(1, 5);
            assertThat(ByteBufUtil.getBytes(payload)).containsExactly(1, 5, 3, 4);
            assertThat(data.array()).containsExactly(1, 2, 3, 4);
        }
    }

    @Test
    void toInputStream() throws Exception {
        assertThat(HttpData.EMPTY_DATA.toInputStream().read()).isEqualTo(-1);

        final InputStream in1 = HttpData.wrap(new byte[] { 1, 2, 3, 4 }).toInputStream();
        assertThat(in1.read()).isOne();
        assertThat(in1.read()).isEqualTo(2);
        assertThat(in1.read()).isEqualTo(3);
        assertThat(in1.read()).isEqualTo(4);
        assertThat(in1.read()).isEqualTo(-1);

        final InputStream in2 = HttpData.wrap(new byte[] { 1, 2, 3, 4 }, 1, 2).toInputStream();
        assertThat(in2.read()).isEqualTo(2);
        assertThat(in2.read()).isEqualTo(3);
        assertThat(in2.read()).isEqualTo(-1);

        final ByteBuf buf = Unpooled.copiedBuffer(new byte[] { 1, 2, 3, 4 });
        assertThat(buf.refCnt()).isOne();
        try (InputStream in3 = HttpData.wrap(buf).toInputStream()) {
            assertThat(in3.read()).isOne();
            assertThat(in3.read()).isEqualTo(2);
            assertThat(in3.read()).isEqualTo(3);
            assertThat(in3.read()).isEqualTo(4);
            assertThat(in3.read()).isEqualTo(-1);
        }
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void toReader() throws Exception {
        final Reader in = HttpData.ofUtf8("가A").toReader(StandardCharsets.UTF_8);
        assertThat(in.read()).isEqualTo((int) '가');
        assertThat(in.read()).isEqualTo((int) 'A');
        assertThat(in.read()).isEqualTo(-1);
    }

    @Test
    void toReaderUtf8() throws Exception {
        final Reader in = HttpData.ofUtf8("あB").toReaderUtf8();
        assertThat(in.read()).isEqualTo((int) 'あ');
        assertThat(in.read()).isEqualTo((int) 'B');
        assertThat(in.read()).isEqualTo(-1);
    }

    @Test
    void toReaderAscii() throws Exception {
        final Reader in = HttpData.ofUtf8("天C").toReaderAscii();
        // '天' will be decoded into 3 bytes of unknown characters
        assertThat(in.read()).isEqualTo(65533);
        assertThat(in.read()).isEqualTo(65533);
        assertThat(in.read()).isEqualTo(65533);
        assertThat(in.read()).isEqualTo((int) 'C');
        assertThat(in.read()).isEqualTo(-1);
    }

    @Test
    void fromUtf8CharSequence() throws Exception {
        assertThat(HttpData.ofUtf8((CharSequence) "가A").toStringUtf8()).isEqualTo("가A");
        assertThat(HttpData.ofUtf8(CharBuffer.wrap("あB")).toStringUtf8()).isEqualTo("あB");
    }

    @Test
    void fromAsciiCharSequence() throws Exception {
        assertThat(HttpData.ofAscii((CharSequence) "가A").toStringUtf8()).isEqualTo("?A");
        assertThat(HttpData.ofAscii(CharBuffer.wrap("あB")).toStringUtf8()).isEqualTo("?B");
    }
}
