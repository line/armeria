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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class DefaultHttpDataTest {

    @Test
    void empty() {
        final HttpData data = DefaultHttpData.empty;
        assertThat(data.array()).isEmpty();
        assertThat(data.byteBuf()).isSameAs(Unpooled.EMPTY_BUFFER);
        assertThat(data.isEmpty()).isTrue();
        assertThat(data.isEndOfStream()).isFalse();
        assertThat(data.withEndOfStream()).isSameAs(DefaultHttpData.emptyEos);
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
        final HttpData data = DefaultHttpData.emptyEos;
        assertThat(data.array()).isEmpty();
        assertThat(data.byteBuf()).isSameAs(Unpooled.EMPTY_BUFFER);
        assertThat(data.isEmpty()).isTrue();
        assertThat(data.isEndOfStream()).isTrue();
        assertThat(data.withEndOfStream()).isSameAs(data);
        assertThat(data.withEndOfStream(false)).isSameAs(DefaultHttpData.empty);
        assertThat(data.isPooled()).isFalse();
    }

    @Test
    void arrayBacked() {
        final byte[] array = { 1, 2, 3, 4 };
        final HttpData data = HttpData.wrap(array);
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

    @Test
    void unpooled() {
        final byte[] array = { 1, 2, 3, 4 };
        final ByteBuf buf = Unpooled.wrappedBuffer(array);
        final HttpData data = DefaultHttpData.of(buf, false);
        assertThat(data.isPooled()).isFalse();
        assertThat(data.length()).isEqualTo(4);
        assertThat(data.array()).isSameAs(buf.array());
        assertThat(data.array()).isSameAs(data.array()); // Should be cached
        assertThat(data.withEndOfStream(false)).isSameAs(data);

        final HttpData dataEoS = data.withEndOfStream();
        assertThat(dataEoS.isPooled()).isFalse();
        assertThat(dataEoS.array()).isSameAs(data.array());
        assertThat(dataEoS.withEndOfStream()).isSameAs(dataEoS);

        // close() on an unpooled data should not release the buffer.
        data.close();
        assertThat(buf.refCnt()).isOne();
        buf.release();
    }
}
