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

package com.linecorp.armeria.common.encoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.compression.BrotliDecoder;

@EnabledIf("io.netty.handler.codec.compression.Brotli#isAvailable")
class BrotliStreamDecoderTest {
    private static final byte[] PAYLOAD = { -117, 1, -128, 77, 101, 111, 119, 3};

    StreamDecoder newDecoder() {
        return new BrotliStreamDecoder(new BrotliDecoder(), ByteBufAllocator.DEFAULT);
    }

    @Test
    void notEmpty() throws IOException {
        final StreamDecoder decoder = newDecoder();
        final ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        buf.writeBytes(PAYLOAD);
        final HttpData data = decoder.decode(HttpData.wrap(buf));
        assertThat(buf.refCnt()).isZero();
        assertThat(data.byteBuf().refCnt()).isOne();
        data.close();
    }

    @Test
    public void empty_unpooled() {
        final StreamDecoder decoder = newDecoder();
        final HttpData data = decoder.decode(HttpData.empty());
        assertThat(data.isPooled()).isFalse();
    }

    @Test
    public void empty_pooled() {
        final StreamDecoder decoder = newDecoder();
        final ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        final HttpData data = decoder.decode(HttpData.wrap(buf));
        assertThat(buf.refCnt()).isZero();

        // Even for a pooled empty input, the result is unpooled since there's no point in pooling empty
        // buffers.
        assertThat(data.isPooled()).isFalse();
    }
}
