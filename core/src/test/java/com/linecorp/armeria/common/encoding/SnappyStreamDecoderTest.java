/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.Random;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.SnappyFrameEncoder;

class SnappyStreamDecoderTest {

    @Test
    void decodeMultipleFramesInOneChunk() {
        final byte[] original = new byte[200_000];
        new Random(1).nextBytes(original);

        final EmbeddedChannel encoder = new EmbeddedChannel(new SnappyFrameEncoder());
        encoder.writeOutbound(Unpooled.wrappedBuffer(original));
        encoder.finish();

        final ByteBuf encoded = Unpooled.buffer();
        for (;;) {
            final ByteBuf frame = encoder.readOutbound();
            if (frame == null) {
                break;
            }
            try {
                encoded.writeBytes(frame);
            } finally {
                frame.release();
            }
        }

        final StreamDecoder decoder = StreamDecoderFactory.snappy().newDecoder(ByteBufAllocator.DEFAULT);
        try (HttpData decoded = decoder.decode(HttpData.wrap(encoded))) {
            assertThat(decoded.array()).containsExactly(original);
        }
    }
}

