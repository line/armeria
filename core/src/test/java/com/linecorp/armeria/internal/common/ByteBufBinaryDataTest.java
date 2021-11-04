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

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.internal.common.ByteArrayBinaryData;
import com.linecorp.armeria.internal.common.ByteBufBinaryData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class ByteBufBinaryDataTest {

    @Test
    void unpooled() {
        final byte[] array = { 1, 2, 3, 4 };
        final ByteBuf buf = Unpooled.wrappedBuffer(array);
        final ByteBufBinaryData data = new ByteBufBinaryData(buf, false);
        assertThat(data.isPooled()).isFalse();
        assertThat(data.length()).isEqualTo(4);
        assertThat(data.array()).isSameAs(buf.array());
        assertThat(data.array()).isSameAs(data.array()); // Should be cached

        // close() on an unpooled data should not release the buffer.
        data.close();
        assertThat(buf.refCnt()).isOne();
        buf.release();
    }

    @Test
    void unpooledSlicedArray() {
        final byte[] array = { 1, 2, 3, 4 };
        final ByteBuf buf = Unpooled.wrappedBuffer(array, 1, 2);
        final ByteBufBinaryData data = new ByteBufBinaryData(buf, false);
        assertThat(data.length()).isEqualTo(2);

        final byte[] slicedArray = data.array();
        assertThat(slicedArray).containsExactly(2, 3);
        // The array should be cached.
        assertThat(data.array()).isSameAs(slicedArray);
    }

    @Test
    void byteBuf() {
        final ByteBuf buf = Unpooled.buffer(4).writeInt(0x01020304);
        final ByteBufBinaryData data = new ByteBufBinaryData(buf, true);
        assertThat(data.isPooled()).isTrue();

        // Test DUPLICATE mode.
        final ByteBuf duplicate = data.byteBuf();
        assertThat(duplicate.isDirect()).isFalse();
        assertThat(duplicate.refCnt()).isOne();
        assertThat(duplicate.readInt()).isEqualTo(0x01020304);
        // Each byteBuf() call has to return a new duplicate.
        assertThat(data.byteBuf().readableBytes()).isEqualTo(4);

        // Test RETAINED_DUPLICATE mode.
        final ByteBuf retained = data.byteBuf(ByteBufAccessMode.RETAINED_DUPLICATE);
        assertThat(retained.isDirect()).isFalse();
        assertThat(retained.refCnt()).isEqualTo(2);
        assertThat(retained.readInt()).isEqualTo(0x01020304);

        // Test DIRECT mode.
        final ByteBuf copied = data.byteBuf(ByteBufAccessMode.FOR_IO);
        assertThat(copied.isDirect()).isTrue();
        assertThat(copied.refCnt()).isOne();
        assertThat(copied.readInt()).isEqualTo(0x01020304);

        // Copied buffer should have its own refCnt, without affecting buf.refCnt().
        assertThat(buf.refCnt()).isEqualTo(2);
        copied.release();
        assertThat(buf.refCnt()).isEqualTo(2);

        // Other buffers should affect buf.refCnt().
        retained.release();
        assertThat(buf.refCnt()).isOne();
        data.close();
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void slicedByteBuf() {
        final ByteBuf buf = Unpooled.buffer(4).writeInt(0x01020304);
        final ByteBufBinaryData data = new ByteBufBinaryData(buf, true);
        assertThat(data.isPooled()).isTrue();

        // Test DUPLICATE mode.
        final ByteBuf slice = data.byteBuf(1, 2, ByteBufAccessMode.DUPLICATE);
        assertThat(slice.isDirect()).isFalse();
        assertThat(slice.refCnt()).isOne();
        assertThat(slice.readUnsignedShort()).isEqualTo(0x0203);

        // Test RETAINED_DUPLICATE mode.
        final ByteBuf retained = data.byteBuf(1, 2, ByteBufAccessMode.RETAINED_DUPLICATE);
        assertThat(retained.isDirect()).isFalse();
        assertThat(retained.refCnt()).isEqualTo(2);
        assertThat(retained.readUnsignedShort()).isEqualTo(0x0203);

        // Test DIRECT mode.
        final ByteBuf copied = data.byteBuf(1, 2, ByteBufAccessMode.FOR_IO);
        assertThat(copied.isDirect()).isTrue();
        assertThat(copied.refCnt()).isOne();
        assertThat(copied.readUnsignedShort()).isEqualTo(0x0203);

        // Copied buffer should have its own refCnt, without affecting buf.refCnt().
        assertThat(buf.refCnt()).isEqualTo(2);
        copied.release();
        assertThat(buf.refCnt()).isEqualTo(2);

        // Other buffers should affect buf.refCnt().
        retained.release();
        assertThat(buf.refCnt()).isOne();
        data.close();
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void directBufferShouldNotBeCopied() {
        final ByteBuf buf = Unpooled.directBuffer(4).writeInt(0x01020304);
        final ByteBufBinaryData data = new ByteBufBinaryData(buf, true);

        final ByteBuf duplicate = data.byteBuf(ByteBufAccessMode.FOR_IO);
        final ByteBuf slice = data.byteBuf(1, 2, ByteBufAccessMode.FOR_IO);
        assertThat(duplicate.memoryAddress()).isEqualTo(buf.memoryAddress());
        assertThat(slice.memoryAddress()).isEqualTo(buf.memoryAddress() + 1);

        assertThat(buf.refCnt()).isEqualTo(3);
        duplicate.release();
        assertThat(buf.refCnt()).isEqualTo(2);
        slice.release();
        assertThat(buf.refCnt()).isOne();
        data.close();
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void doubleFree() {
        final ByteBuf buf = Unpooled.directBuffer(4).writeInt(0x01020304).retain();
        final ByteBufBinaryData data = new ByteBufBinaryData(buf, true);
        for (int i = 0; i < 2; i++) {
            data.close();
            assertThat(buf.refCnt()).isOne();
        }
        buf.release();
    }

    @Test
    void hash() {
        final ByteBufBinaryData data =
                new ByteBufBinaryData(Unpooled.directBuffer().writeInt(0x02030405), true);
        assertThat(data.hashCode()).isEqualTo(((2 * 31 + 3) * 31 + 4) * 31 + 5);
        data.close();

        // Ensure 33rd+ bytes are ignored.
        final ByteBufBinaryData bigData = new ByteBufBinaryData(Unpooled.directBuffer()
                                                                    .writeZero(32)
                                                                    .writeByte(1),
                                                            true);
        assertThat(bigData.hashCode()).isZero();
        bigData.close();
    }

    @Test
    void equals() {
        final ByteBufBinaryData a = new ByteBufBinaryData(Unpooled.directBuffer().writeInt(0x01020304), true);
        final ByteBufBinaryData b = new ByteBufBinaryData(Unpooled.directBuffer().writeInt(0x01020304), true);
        final ByteBufBinaryData c = new ByteBufBinaryData(Unpooled.directBuffer().writeMedium(0x010203), true);
        final ByteBufBinaryData d = new ByteBufBinaryData(Unpooled.directBuffer().writeInt(0x04050607), true);
        final ByteArrayBinaryData arrayData = new ByteArrayBinaryData(new byte[] { 1, 2, 3, 4 });

        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.array()).isEqualTo(arrayData.array());
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(d);
        assertThat(a).isNotEqualTo(new Object());

        a.close();
        b.close();
        c.close();
        d.close();
    }

    @Test
    void testToString() {
        assertThat(new ByteBufBinaryData(Unpooled.copiedBuffer("foo", StandardCharsets.US_ASCII), false))
                .hasToString("{3B, text=foo}");
        assertThat(new ByteBufBinaryData(Unpooled.copiedBuffer("\u0001\u0002", StandardCharsets.US_ASCII),
                                         false))
                .hasToString("{2B, hex=0102}");

        // pooled
        assertThat(new ByteBufBinaryData(Unpooled.copiedBuffer("foo", StandardCharsets.US_ASCII), true))
                .hasToString("{3B, pooled, text=foo}");
        assertThat(new ByteBufBinaryData(Unpooled.copiedBuffer("\u0001\u0002", StandardCharsets.US_ASCII),
                                         true))
                .hasToString("{2B, pooled, hex=0102}");

        // closed and freed
        final ByteBufBinaryData data1 =
                new ByteBufBinaryData(Unpooled.copiedBuffer("bar", StandardCharsets.US_ASCII), true);
        data1.close();
        assertThat(data1).hasToString("{3B, pooled, closed}");

        // closed but not freed
        final ByteBufBinaryData data2 =
                new ByteBufBinaryData(Unpooled.unreleasableBuffer(
                        Unpooled.copiedBuffer("bar", StandardCharsets.US_ASCII)), true);
        data2.close();
        assertThat(data2).hasToString("{3B, pooled, closed, text=bar}");

        // Longer than 16 bytes
        assertThat(new ByteBufBinaryData(Unpooled.copiedBuffer("0123456789abcdef\u0001",
                                                             StandardCharsets.US_ASCII), false))
                .hasToString("{17B, text=0123456789abcdef}");
    }
}
