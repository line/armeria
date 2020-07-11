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
package com.linecorp.armeria.common.unsafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

class ByteBufHttpDataTest {

    @Test
    void empty() {
        final ByteBufHttpData data = ByteBufHttpData.EMPTY;
        assertThat(data.array()).isEmpty();
        assertThat(data.content()).isSameAs(Unpooled.EMPTY_BUFFER);
        assertThat(data.isEmpty()).isTrue();
        assertThat(data.isEndOfStream()).isFalse();
        assertThat(data.withEndOfStream()).isSameAs(ByteBufHttpData.EMPTY_EOS);
        assertThat(data.withEndOfStream(false)).isSameAs(data);

        for (int i = 0; i < 2; i++) {
            // close() should not release anything.
            data.close();
            assertThat(data.content().refCnt()).isGreaterThan(0);
        }
    }

    @Test
    void emptyEoS() {
        final ByteBufHttpData data = ByteBufHttpData.EMPTY_EOS;
        assertThat(data.array()).isEmpty();
        assertThat(data.content()).isSameAs(Unpooled.EMPTY_BUFFER);
        assertThat(data.isEmpty()).isTrue();
        assertThat(data.isEndOfStream()).isTrue();
        assertThat(data.withEndOfStream()).isSameAs(data);
        assertThat(data.withEndOfStream(false)).isSameAs(ByteBufHttpData.EMPTY);
    }

    @Test
    void arrayBacked() {
        final byte[] array = { 1, 2, 3, 4 };
        final ByteBuf buf = Unpooled.wrappedBuffer(array);
        final ByteBufHttpData data = new ByteBufHttpData(buf, false);
        assertThat(data.array()).isSameAs(array);
        assertThat(data.content()).isSameAs(buf);
        assertThat(data.isEmpty()).isFalse();
        assertThat(data.length()).isSameAs(4);
        assertThat(data.isEndOfStream()).isFalse();
        assertThat(data.withEndOfStream(false)).isSameAs(data);

        final PooledHttpData dataEoS = data.withEndOfStream();
        assertThat(dataEoS.array()).isSameAs(array);
        assertThat(dataEoS.content()).isSameAs(buf);
        assertThat(dataEoS.isEmpty()).isFalse();
        assertThat(dataEoS.length()).isSameAs(4);
        assertThat(dataEoS.isEndOfStream()).isTrue();
        assertThat(dataEoS.withEndOfStream()).isSameAs(dataEoS);
        assertThat(dataEoS.withEndOfStream(false).isEndOfStream()).isFalse();
    }

    @Test
    void arrayBackedSlice() throws IOException {
        final ByteBuf buf = Unpooled.wrappedBuffer(new byte[] { 1, 2, 3, 4 });
        final ByteBuf leftSlicedBuf = buf.slice(1, 3);
        final ByteBuf rightSlicedBuf = buf.slice(0, 3);
        final ByteBuf bothSlicedBuf = buf.slice(1, 2);

        final ByteBufHttpData leftSlicedData = new ByteBufHttpData(leftSlicedBuf, false);
        assertThat(leftSlicedData.length()).isEqualTo(3);
        assertThat(leftSlicedData.array()).containsExactly(2, 3, 4);
        assertThat(leftSlicedData.content()).isSameAs(leftSlicedBuf);
        assertThat(ByteStreams.toByteArray(leftSlicedData.toInputStream())).containsExactly(2, 3, 4);

        final ByteBufHttpData rightSlicedData = new ByteBufHttpData(rightSlicedBuf, false);
        assertThat(rightSlicedData.length()).isEqualTo(3);
        assertThat(rightSlicedData.array()).containsExactly(1, 2, 3);
        assertThat(rightSlicedData.content()).isSameAs(rightSlicedBuf);
        assertThat(ByteStreams.toByteArray(rightSlicedData.toInputStream())).containsExactly(1, 2, 3);

        final ByteBufHttpData bothSlicedData = new ByteBufHttpData(bothSlicedBuf, false);
        assertThat(bothSlicedData.length()).isEqualTo(2);
        assertThat(bothSlicedData.array()).containsExactly(2, 3);
        assertThat(bothSlicedData.content()).isSameAs(bothSlicedBuf);
        assertThat(ByteStreams.toByteArray(bothSlicedData.toInputStream())).containsExactly(2, 3);

        // Ensure getByte() gets the byte from the right position.
        assertThat(bothSlicedData.getByte(0)).isEqualTo((byte) 2);
        assertThat(bothSlicedData.getByte(1)).isEqualTo((byte) 3);
        assertThatThrownBy(() -> bothSlicedData.getByte(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bothSlicedData.getByte(2)).isInstanceOf(IndexOutOfBoundsException.class);

        // Ensure toString() gets the bytes from the right position.
        assertThat(bothSlicedData.toString(StandardCharsets.ISO_8859_1)).isEqualTo("\u0002\u0003");
    }

    @Test
    void directBufferBacked() throws IOException {
        final byte[] array = { 1, 2, 3, 4 };
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer().writeBytes(array);
        final ByteBufHttpData data = new ByteBufHttpData(buf, false);
        assertThat(data.content()).isSameAs(buf);
        assertThat(data.array()).isNotSameAs(array).containsExactly(1, 2, 3, 4);
        assertThat(data.refCnt()).isOne();
        assertThat(ByteStreams.toByteArray(data.toInputStream())).containsExactly(1, 2, 3, 4);
        data.close();
        assertThat(data.refCnt()).isZero();
        assertThat(data.content().refCnt()).isZero();
        // Make sure toString() does not fail after released.
        assertThat(data.toString()).contains("freed");
    }
}
