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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class ByteArrayHttpDataTest {

    @Test
    void empty() {
        final ByteArrayHttpData data = ByteArrayHttpData.EMPTY;
        assertThat(data.array()).isEmpty();
        assertThat(data.byteBuf()).isSameAs(Unpooled.EMPTY_BUFFER);
        assertThat(data.isEmpty()).isTrue();
        assertThat(data.isEndOfStream()).isFalse();
        assertThat(data.withEndOfStream()).isSameAs(ByteArrayHttpData.EMPTY_EOS);
        assertThat(data.withEndOfStream(false)).isSameAs(data);
        assertThat(data.isPooled()).isFalse();

        for (int i = 0; i < 2; i++) {
            // close() should not release anything.
            data.close();
            assertThat(data.byteBuf().refCnt()).isOne();
        }
    }

    @Test
    void emptyEoS() {
        final ByteArrayHttpData data = ByteArrayHttpData.EMPTY_EOS;
        assertThat(data.array()).isEmpty();
        assertThat(data.byteBuf()).isSameAs(Unpooled.EMPTY_BUFFER);
        assertThat(data.isEmpty()).isTrue();
        assertThat(data.isEndOfStream()).isTrue();
        assertThat(data.withEndOfStream()).isSameAs(data);
        assertThat(data.withEndOfStream(false)).isSameAs(ByteArrayHttpData.EMPTY);
        assertThat(data.isPooled()).isFalse();
    }

    @Test
    void arrayBacked() {
        final byte[] array = { 1, 2, 3, 4 };
        final ByteArrayHttpData data = new ByteArrayHttpData(array);
        assertThat(data.array()).isSameAs(array);
        assertThat(data.byteBuf().array()).isSameAs(array);
        assertThat(data.isEmpty()).isFalse();
        assertThat(data.length()).isEqualTo(4);
        assertThat(data.isEndOfStream()).isFalse();
        assertThat(data.withEndOfStream(false)).isSameAs(data);
        assertThat(data.toInputStream()).hasBinaryContent(array);
        assertThat(data.isPooled()).isFalse();

        final HttpData dataEoS = data.withEndOfStream();
        assertThat(dataEoS.array()).isSameAs(array);
        assertThat(dataEoS.byteBuf().array()).isSameAs(array);
        assertThat(dataEoS.isEmpty()).isFalse();
        assertThat(dataEoS.length()).isSameAs(4);
        assertThat(dataEoS.isEndOfStream()).isTrue();
        assertThat(dataEoS.withEndOfStream()).isSameAs(dataEoS);
        assertThat(dataEoS.withEndOfStream(false).isEndOfStream()).isFalse();
        assertThat(dataEoS.toInputStream()).hasBinaryContent(array);
        assertThat(dataEoS.isPooled()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ByteBufAccessMode.class, names = { "DUPLICATE", "RETAINED_DUPLICATE" })
    void duplicateOrSlice(ByteBufAccessMode mode) {
        final byte[] array = { 1, 2, 3, 4 };
        final ByteArrayHttpData data = new ByteArrayHttpData(array);
        final ByteBuf buf = data.byteBuf(mode);
        assertThat(buf.isDirect()).isFalse();
        assertThat(buf.readableBytes()).isEqualTo(4);
        assertThat(buf.readInt()).isEqualTo(0x01020304);
        assertThat(buf.array()).isSameAs(array);

        final ByteBuf slicedBuf = data.byteBuf(1, 2, mode);
        assertThat(slicedBuf.isDirect()).isFalse();
        assertThat(slicedBuf.readableBytes()).isEqualTo(2);
        assertThat(slicedBuf.readUnsignedShort()).isEqualTo(0x0203);
        // Ensure the changes affects the original array.
        slicedBuf.setShort(0, 0x0506);
        assertThat(array).containsExactly(1, 5, 6, 4);

        // Making an empty slice
        assertThat(data.byteBuf(0, 0, mode)).isSameAs(Unpooled.EMPTY_BUFFER);
    }

    @Test
    void directCopy() {
        final ByteArrayHttpData data = new ByteArrayHttpData(new byte[] { 1, 2, 3, 4 });
        final ByteBuf buf = data.byteBuf(ByteBufAccessMode.DIRECT);
        assertThat(buf.isDirect()).isTrue();
        assertThat(buf.readableBytes()).isEqualTo(4);
        assertThat(buf.readInt()).isEqualTo(0x01020304);
        buf.release();

        final ByteBuf slicedBuf = data.byteBuf(1, 2, ByteBufAccessMode.DIRECT);
        assertThat(slicedBuf.isDirect()).isTrue();
        assertThat(slicedBuf.readableBytes()).isEqualTo(2);
        assertThat(slicedBuf.readUnsignedShort()).isEqualTo(0x0203);
        slicedBuf.release();
    }

    @Test
    void hash() {
        final ByteArrayHttpData data = new ByteArrayHttpData(new byte[] { 2, 3, 4, 5 });
        assertThat(data.hashCode()).isEqualTo(((2 * 31 + 3) * 31 + 4) * 31 + 5);

        // Ensure 33rd+ bytes are ignored.
        final byte[] bigArray = new byte[33];
        bigArray[32] = 1;
        final ByteArrayHttpData bigData = new ByteArrayHttpData(bigArray);
        assertThat(bigData.hashCode()).isZero();
    }

    @Test
    void equals() {
        final HttpData a = new ByteArrayHttpData(new byte[] { 1, 2, 3, 4 });
        final HttpData b = new ByteArrayHttpData(new byte[] { 1, 2, 3, 4 });
        final HttpData c = new ByteArrayHttpData(new byte[] { 1, 2, 3 });
        final HttpData d = new ByteArrayHttpData(new byte[] { 4, 5, 6, 7 });
        final HttpData bufData = new ByteBufHttpData(Unpooled.directBuffer().writeInt(0x01020304), true);

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a).isEqualTo(bufData);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(d);
        assertThat(a).isNotEqualTo(new Object());

        bufData.close();
    }

    @Test
    void testToString() {
        assertThat(ByteArrayHttpData.EMPTY).hasToString("{0B}");
        assertThat(new ByteArrayHttpData(new byte[] { 'f', 'o', 'o' })).hasToString("{3B, text=foo}");
        assertThat(new ByteArrayHttpData(new byte[] { 1, 2, 3 })).hasToString("{3B, hex=010203}");

        // endOfStream
        assertThat(ByteArrayHttpData.EMPTY_EOS).hasToString("{0B, EOS}");
        assertThat(new ByteArrayHttpData(new byte[] { 'f', 'o', 'o' })
                           .withEndOfStream()).hasToString("{3B, EOS, text=foo}");
        assertThat(new ByteArrayHttpData(new byte[] { 1, 2, 3 })
                           .withEndOfStream()).hasToString("{3B, EOS, hex=010203}");

        // Longer than 16 bytes
        assertThat(new ByteArrayHttpData(new byte[] {
                '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', -1
        })).hasToString("{17B, text=0123456789abcdef}");
    }

    @Test
    void testToStringCache() {
        final ByteArrayHttpData data = new ByteArrayHttpData(new byte[] { 'b', 'a', 'r' });
        final String str = data.toString();
        assertThat(data.toString()).isSameAs(str);
    }
}
