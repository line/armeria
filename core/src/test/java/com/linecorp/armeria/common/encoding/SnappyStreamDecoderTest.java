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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.SnappyFrameEncoder;

class SnappyStreamDecoderTest {

    @Test
    void singleFrameDecompression() {
        final byte[] plain = "hello snappy".getBytes();
        final ByteBuf framed = encode(plain);
        final StreamDecoder dec = StreamDecoderFactory.snappy().newDecoder(ByteBufAllocator.DEFAULT);
        try (HttpData decoded = dec.decode(HttpData.wrap(framed))) {
            assertThat(decoded.length()).isEqualTo(plain.length);
            assertThat(decoded.array()).isEqualTo(plain);
        }
        try (HttpData finished = dec.finish()) {
            assertThat(finished.isEmpty()).isTrue();
        }
    }

    @Test
    void multiFrameChunkDecompression() {
        final byte[] plain = new byte[200_000];
        final Random r = new Random(1);
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ('a' + r.nextInt(4));
        }

        final ByteBuf framed = encode(plain);
        final StreamDecoder dec = StreamDecoderFactory.snappy().newDecoder(ByteBufAllocator.DEFAULT);
        try (HttpData decoded = dec.decode(HttpData.wrap(framed))) {
            assertThat(decoded.length()).isEqualTo(200_000);
            assertThat(decoded.array()).isEqualTo(plain);
        }
        try (HttpData finished = dec.finish()) {
            assertThat(finished.isEmpty()).isTrue();
        }
    }

    @Test
    void streamingChunksDecompression() throws Exception {
        final byte[] plain = new byte[200_000];
        final Random r = new Random(1);
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ('a' + r.nextInt(4));
        }

        final ByteBuf framed = encode(plain);
        final int split = framed.readableBytes() / 2;
        final ByteBuf chunk1 = framed.readRetainedSlice(split);
        final ByteBuf chunk2 = framed;

        final StreamDecoder dec = StreamDecoderFactory.snappy().newDecoder(ByteBufAllocator.DEFAULT);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        try (HttpData decoded1 = dec.decode(HttpData.wrap(chunk1))) {
            bos.write(decoded1.array());
        }
        try (HttpData decoded2 = dec.decode(HttpData.wrap(chunk2))) {
            bos.write(decoded2.array());
        }
        try (HttpData finished = dec.finish()) {
            bos.write(finished.array());
        }

        assertThat(bos.toByteArray()).isEqualTo(plain);
    }

    @Test
    void emptyChunk() {
        final StreamDecoder dec = StreamDecoderFactory.snappy().newDecoder(ByteBufAllocator.DEFAULT);
        try (HttpData decoded = dec.decode(HttpData.empty())) {
            assertThat(decoded.isEmpty()).isTrue();
        }
        try (HttpData finished = dec.finish()) {
            assertThat(finished.isEmpty()).isTrue();
        }
    }

    @Test
    void exceedsMaxLength() {
        final byte[] plain = new byte[200_000];
        final Random r = new Random(1);
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ('a' + r.nextInt(4));
        }

        final ByteBuf framed = encode(plain);
        final StreamDecoder dec = StreamDecoderFactory.snappy().newDecoder(ByteBufAllocator.DEFAULT, 50_000);
        assertThatThrownBy(() -> dec.decode(HttpData.wrap(framed)))
                .isInstanceOf(ContentTooLargeException.class)
                .satisfies(cause -> {
                    final ContentTooLargeException tooLarge = (ContentTooLargeException) cause;
                    assertThat(tooLarge.maxContentLength()).isEqualTo(50_000);
                });
    }

    private static ByteBuf encode(byte[] data) {
        final EmbeddedChannel enc = new EmbeddedChannel(new SnappyFrameEncoder());
        enc.writeOutbound(Unpooled.wrappedBuffer(data));
        enc.finish();
        final ByteBuf framed = Unpooled.buffer();
        for (ByteBuf b; (b = enc.readOutbound()) != null;) {
            framed.writeBytes(b);
            b.release();
        }
        return framed;
    }
}
