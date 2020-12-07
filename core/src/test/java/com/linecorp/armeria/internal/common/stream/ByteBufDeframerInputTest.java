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

package com.linecorp.armeria.internal.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

class ByteBufDeframerInputTest {

    ByteBufDeframerInput input;
    List<ByteBuf> byteBufs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        input = new ByteBufDeframerInput(UnpooledByteBufAllocator.DEFAULT);
        final ByteBuf byteBuf1 = Unpooled.wrappedBuffer(new byte[]{ 1, 2, 3, 4 });
        input.add(byteBuf1);
        byteBufs.add(byteBuf1);
        final ByteBuf byteBuf2 = Unpooled.buffer(4);
        byteBuf2.writeByte(5);
        byteBuf2.writeByte(6);
        byteBuf2.writeByte(7);
        input.add(byteBuf2);
        byteBufs.add(byteBuf2);
        final ByteBuf byteBuf3 = Unpooled.wrappedBuffer(new byte[]{ 8 });
        input.add(byteBuf3);
        byteBufs.add(byteBuf3);
        final ByteBuf byteBuf4 = Unpooled.EMPTY_BUFFER;
        input.add(byteBuf4);
        byteBufs.add(byteBuf4);
        final ByteBuf byteBuf5 = Unpooled.wrappedBuffer(new byte[]{ -1, 9 });
        byteBuf5.readByte();
        input.add(byteBuf5);
        byteBufs.add(byteBuf5);

        final ByteBuf byteBuf6 = Unpooled.wrappedBuffer(new byte[]{ -1 });
        byteBuf6.readByte();
        input.add(byteBuf6);
        byteBufs.add(byteBuf6);
    }

    @AfterEach
    void tearDown() {
        input.close();
        for (ByteBuf byteBuf : byteBufs) {
            if (byteBuf != Unpooled.EMPTY_BUFFER) {
                assertThat(byteBuf.refCnt()).isZero();
            }
        }
    }

    @Test
    void readByte() {
        for (int i = 1; i < 10; i++) {
            assertThat(input.readByte()).isEqualTo((byte) i);
        }
        assertThatThrownBy(() -> input.readByte())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("end of deframer input");
    }

    @Test
    void readableBytes() {
        assertThat(input.readableBytes()).isEqualTo(9);
        for (int i = 1; i < 10; i++) {
            assertThat(input.readByte()).isEqualTo((byte) i);
            assertThat(input.readableBytes()).isEqualTo(9 - i);
        }

        final ByteBufDeframerInput empty = new ByteBufDeframerInput(UnpooledByteBufAllocator.DEFAULT);
        assertThat(empty.readableBytes()).isEqualTo(0);
    }

    @Test
    void readInt() {
        // fast path
        final int expected1 = 1 << 24 | 2 << 16 | 3 << 8 | 4;
        assertThat(input.readInt()).isEqualTo(expected1);
        assertThat(input.readableBytes()).isEqualTo(5);
        assertThat(byteBufs.get(0).refCnt()).isZero();

        // slow path
        final int expected2 = 5 << 24 | 6 << 16 | 7 << 8 | 8;
        assertThat(input.readInt()).isEqualTo(expected2);
        assertThat(input.readableBytes()).isEqualTo(1);
        assertThat(byteBufs.get(1).refCnt()).isZero();
        assertThat(byteBufs.get(2).refCnt()).isZero();

        assertThatThrownBy(() -> input.readInt())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("end of deframer input");
    }

    @Test
    void readUnsignedBytes() {
        try (ByteBufDeframerInput input = new ByteBufDeframerInput(UnpooledByteBufAllocator.DEFAULT)) {
            input.add(Unpooled.wrappedBuffer(new byte[]{ 1 }));
            input.add(Unpooled.wrappedBuffer(new byte[]{ -1 }));
            assertThat(input.readUnsignedByte()).isEqualTo((byte) 1);
            assertThat((byte) input.readUnsignedByte()).isEqualTo((byte) 0xFF);
        }
    }

    @Test
    void readBytes() {
        final ByteBufDeframerInput input = new ByteBufDeframerInput(UnpooledByteBufAllocator.DEFAULT);
        final ByteBuf byteBuf1 = Unpooled.wrappedBuffer(new byte[]{ 1, 2, 3, 4 });
        final ByteBuf byteBuf2 = Unpooled.buffer(4);
        byteBuf2.writeByte(5);
        byteBuf2.writeByte(6);
        final ByteBuf byteBuf3 = Unpooled.wrappedBuffer(new byte[]{ 7, 8 });
        final ByteBuf byteBuf4 = Unpooled.wrappedBuffer(new byte[]{ -1, 9 });
        byteBuf4.readByte();

        input.add(byteBuf1);
        input.add(byteBuf2);
        input.add(byteBuf3);
        input.add(Unpooled.EMPTY_BUFFER);
        input.add(byteBuf4);

        // Should return byteBuf1 without additional copies
        ByteBuf buf = input.readBytes(4);
        assertThat(buf).isSameAs(byteBuf1);
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.release();

        // Should return retained slice ByteBuf from byteBuf2
        buf = input.readBytes(1);
        assertThat(ByteBufUtil.getBytes(buf)).isEqualTo(new byte[]{ 5 });
        assertThat(buf.unwrap()).isSameAs(byteBuf2);
        assertThat(buf.refCnt()).isEqualTo(2);
        buf.release();

        // Create new ByteBuf and copy data from byteBuf2 and byteBuf3
        buf = input.readBytes(3);
        assertThat(ByteBufUtil.getBytes(buf)).isEqualTo(new byte[]{ 6, 7, 8 });
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.release();

        // Should return byteBuf4 without additional copies
        buf = input.readBytes(1);
        assertThat(ByteBufUtil.getBytes(buf)).isEqualTo(new byte[]{ 9 });
        assertThat(buf).isSameAs(byteBuf4);
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.release();

        assertThatThrownBy(() -> input.readBytes(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("end of deframer input");
        input.close();
        assertThat(byteBuf1.refCnt()).isZero();
        assertThat(byteBuf2.refCnt()).isZero();
        assertThat(byteBuf3.refCnt()).isZero();
        assertThat(byteBuf4.refCnt()).isZero();
    }

    @Test
    void addAfterClosing() {
        assertThat(input.readableBytes()).isEqualTo(9);
        input.close();
        assertThat(input.readableBytes()).isEqualTo(0);
        final ByteBuf byteBuf = Unpooled.wrappedBuffer(new byte[]{ 1, 2, 3, 4 });
        // As 'ByteBufDeframerInput' is closed, the 'byteBuf' should be released by 'ByteBufDeframerInput'.
        input.add(byteBuf);
        assertThat(byteBuf.refCnt()).isZero();
    }
}
