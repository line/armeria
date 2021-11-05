/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.common.ByteBufAccessMode;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class ByteArrayBinaryDataTest {

    @Test
    void arrayBacked() {
        final byte[] array = { 1, 2, 3, 4 };
        final ByteArrayBinaryData data = new ByteArrayBinaryData(array);
        assertThat(data.array()).isSameAs(array);
        assertThat(data.byteBuf().array()).isSameAs(array);
        assertThat(data.isEmpty()).isFalse();
        assertThat(data.length()).isEqualTo(4);
        assertThat(data.toInputStream()).hasBinaryContent(array);
        assertThat(data.isPooled()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ByteBufAccessMode.class, names = { "DUPLICATE", "RETAINED_DUPLICATE" })
    void duplicateOrSlice(ByteBufAccessMode mode) {
        final byte[] array = { 1, 2, 3, 4 };
        final ByteArrayBinaryData data = new ByteArrayBinaryData(array);
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
        final ByteArrayBinaryData data = new ByteArrayBinaryData(new byte[] { 1, 2, 3, 4 });
        final ByteBuf buf = data.byteBuf(ByteBufAccessMode.FOR_IO);
        assertThat(buf.isDirect()).isTrue();
        assertThat(buf.readableBytes()).isEqualTo(4);
        assertThat(buf.readInt()).isEqualTo(0x01020304);
        buf.release();

        final ByteBuf slicedBuf = data.byteBuf(1, 2, ByteBufAccessMode.FOR_IO);
        assertThat(slicedBuf.isDirect()).isTrue();
        assertThat(slicedBuf.readableBytes()).isEqualTo(2);
        assertThat(slicedBuf.readUnsignedShort()).isEqualTo(0x0203);
        slicedBuf.release();
    }

    @Test
    void hash() {
        final ByteArrayBinaryData data = new ByteArrayBinaryData(new byte[] { 2, 3, 4, 5 });
        assertThat(data.hashCode()).isEqualTo(((2 * 31 + 3) * 31 + 4) * 31 + 5);

        // Ensure 33rd+ bytes are ignored.
        final byte[] bigArray = new byte[33];
        bigArray[32] = 1;
        final ByteArrayBinaryData bigData = new ByteArrayBinaryData(bigArray);
        assertThat(bigData.hashCode()).isZero();
    }

    @Test
    void equals() {
        final ByteArrayBinaryData a = new ByteArrayBinaryData(new byte[] { 1, 2, 3, 4 });
        final ByteArrayBinaryData b = new ByteArrayBinaryData(new byte[] { 1, 2, 3, 4 });
        final ByteArrayBinaryData c = new ByteArrayBinaryData(new byte[] { 1, 2, 3 });
        final ByteArrayBinaryData d = new ByteArrayBinaryData(new byte[] { 4, 5, 6, 7 });
        final ByteBufBinaryData bufData =
                new ByteBufBinaryData(Unpooled.directBuffer().writeInt(0x01020304), true);

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.array()).isEqualTo(bufData.array());
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(d);
        assertThat(a).isNotEqualTo(new Object());

        bufData.close();
    }

    @Test
    void testToString() {
        assertThat(ByteArrayBinaryData.empty()).hasToString("{0B}");
        assertThat(new ByteArrayBinaryData(new byte[] { 'f', 'o', 'o' })).hasToString("{3B, text=foo}");
        assertThat(new ByteArrayBinaryData(new byte[] { 1, 2, 3 })).hasToString("{3B, hex=010203}");

        // Longer than 16 bytes
        assertThat(new ByteArrayBinaryData(new byte[] {
                '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', -1
        })).hasToString("{17B, text=0123456789abcdef}");
    }
}
