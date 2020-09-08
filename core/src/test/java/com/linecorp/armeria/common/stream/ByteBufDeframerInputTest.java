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

package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

class ByteBufDeframerInputTest {

    ByteBufDeframerInput input;

    @BeforeEach
    void setUp() {
        input = new ByteBufDeframerInput(UnpooledByteBufAllocator.DEFAULT);
        input.add(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4}));
        final ByteBuf writableBuffer = Unpooled.buffer(4);
        writableBuffer.writeByte(5);
        writableBuffer.writeByte(6);
        input.add(writableBuffer);
        input.add(Unpooled.wrappedBuffer(new byte[]{7, 8}));
        input.add(Unpooled.EMPTY_BUFFER);
        final ByteBuf readableBuffer = Unpooled.wrappedBuffer(new byte[]{-1, 9});
        readableBuffer.readByte();
        input.add(readableBuffer);
    }

    @AfterEach
    void tearDown() {
        input.close();
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
        final int expected1 = 1 << 24 | 2 << 16 | 3 << 8 | 4;
        assertThat(input.readInt()).isEqualTo(expected1);
        final int expected2 = 5 << 24 | 6 << 16 | 7 << 8 | 8;
        assertThat(input.readInt()).isEqualTo(expected2);

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
        final ByteBuf byteBuf1 = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4});
        final ByteBuf byteBuf2 = Unpooled.buffer(4);
        byteBuf2.writeByte(5);
        byteBuf2.writeByte(6);
        final ByteBuf byteBuf3 = Unpooled.wrappedBuffer(new byte[]{7, 8});
        final ByteBuf byteBuf4 = Unpooled.wrappedBuffer(new byte[]{-1, 9});
        byteBuf4.readByte();

        input.add(byteBuf1);
        input.add(byteBuf2);
        input.add(byteBuf3);
        input.add(Unpooled.EMPTY_BUFFER);
        input.add(byteBuf4);

        ByteBuf buf = input.readBytes(4);
        assertThat(ByteBufUtil.getBytes(buf)).isEqualTo(new byte[]{1, 2, 3, 4});
        assertThat(buf.refCnt()).isEqualTo(2);
        assertThat(buf.unwrap()).isSameAs(byteBuf1);
        buf.release();

        buf = input.readBytes(1);
        assertThat(ByteBufUtil.getBytes(buf)).isEqualTo(new byte[]{5});
        assertThat(buf.unwrap()).isSameAs(byteBuf2);
        assertThat(buf.refCnt()).isEqualTo(2);
        buf.release();

        buf = input.readBytes(3);
        assertThat(ByteBufUtil.getBytes(buf)).isEqualTo(new byte[]{6, 7, 8});
        assertThat(buf.refCnt()).isEqualTo(1);
        buf.release();

        buf = input.readBytes(1);
        assertThat(ByteBufUtil.getBytes(buf)).isEqualTo(new byte[]{9});
        assertThat(buf.unwrap()).isSameAs(byteBuf4);

        assertThatThrownBy(() -> input.readBytes(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("end of deframer input");
        buf.release();
        input.close();
    }
}
