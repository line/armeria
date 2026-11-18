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
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.SnappyFrameDecoder;
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

    @Test
    void compositeBufferAllocationFailureReleasesBuffers() {
        final byte[] plain = new byte[200_000];
        final Random r = new Random(1);
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ('a' + r.nextInt(4));
        }

        final ByteBuf framed = encode(plain);
        final ByteBufAllocator failingAlloc = new DelegatingByteBufAllocator() {
            @Override
            public CompositeByteBuf compositeBuffer() {
                throw new OutOfMemoryError("simulated oom");
            }
        };

        final StreamDecoder dec = new SnappyStreamDecoder(new SnappyFrameDecoder(), failingAlloc, -1);
        assertThatThrownBy(() -> dec.decode(HttpData.wrap(framed)))
                .isInstanceOf(OutOfMemoryError.class)
                .hasMessage("simulated oom");
    }

    @Test
    void addComponentsFailureReleasesComposite() {
        final byte[] plain = new byte[200_000];
        final Random r = new Random(1);
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ('a' + r.nextInt(4));
        }

        final ByteBuf framed = encode(plain);
        final CompositeByteBuf failingComposite =
                new CompositeByteBuf(ByteBufAllocator.DEFAULT, false, 16) {
                    @Override
                    public CompositeByteBuf addComponents(boolean increaseWriterIndex,
                                                          Iterable<ByteBuf> buffers) {
                        throw new IllegalStateException("simulated addComponents failure");
                    }
                };

        final ByteBufAllocator failingAlloc = new DelegatingByteBufAllocator() {
            @Override
            public CompositeByteBuf compositeBuffer() {
                return failingComposite;
            }
        };

        final StreamDecoder dec = new SnappyStreamDecoder(new SnappyFrameDecoder(), failingAlloc, -1);
        assertThatThrownBy(() -> dec.decode(HttpData.wrap(framed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated addComponents failure");
    }

    @Test
    void addComponentFailureReleasesComposite() {
        final byte[] plain = new byte[200_000];
        final Random r = new Random(1);
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ('a' + r.nextInt(4));
        }

        final ByteBuf framed = encode(plain);
        final CompositeByteBuf failingComposite =
                new CompositeByteBuf(ByteBufAllocator.DEFAULT, false, 16) {
                    @Override
                    public CompositeByteBuf addComponent(boolean increaseWriterIndex, ByteBuf buffer) {
                        throw new IllegalStateException("simulated addComponent failure");
                    }
                };

        final ByteBufAllocator failingAlloc = new DelegatingByteBufAllocator() {
            @Override
            public CompositeByteBuf compositeBuffer() {
                return failingComposite;
            }
        };

        final StreamDecoder dec = new SnappyStreamDecoder(new SnappyFrameDecoder(), failingAlloc, -1);
        assertThatThrownBy(() -> dec.decode(HttpData.wrap(framed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated addComponent failure");
    }

    @Test
    void finishFailureReleasesInbound() {
        final ChannelHandler failingHandler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                throw new RuntimeException("finish failed");
            }
        };

        final StreamDecoder dec = new AbstractStreamDecoder(failingHandler, ByteBufAllocator.DEFAULT, -1);
        assertThatThrownBy(dec::finish)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("finish failed");
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

    private static class DelegatingByteBufAllocator implements ByteBufAllocator {
        private final ByteBufAllocator delegate = ByteBufAllocator.DEFAULT;

        @Override
        public ByteBuf buffer() {
            return delegate.buffer();
        }

        @Override
        public ByteBuf buffer(int initialCapacity) {
            return delegate.buffer(initialCapacity);
        }

        @Override
        public ByteBuf buffer(int initialCapacity, int maxCapacity) {
            return delegate.buffer(initialCapacity, maxCapacity);
        }

        @Override
        public ByteBuf ioBuffer() {
            return delegate.ioBuffer();
        }

        @Override
        public ByteBuf ioBuffer(int initialCapacity) {
            return delegate.ioBuffer(initialCapacity);
        }

        @Override
        public ByteBuf ioBuffer(int initialCapacity, int maxCapacity) {
            return delegate.ioBuffer(initialCapacity, maxCapacity);
        }

        @Override
        public ByteBuf heapBuffer() {
            return delegate.heapBuffer();
        }

        @Override
        public ByteBuf heapBuffer(int initialCapacity) {
            return delegate.heapBuffer(initialCapacity);
        }

        @Override
        public ByteBuf heapBuffer(int initialCapacity, int maxCapacity) {
            return delegate.heapBuffer(initialCapacity, maxCapacity);
        }

        @Override
        public ByteBuf directBuffer() {
            return delegate.directBuffer();
        }

        @Override
        public ByteBuf directBuffer(int initialCapacity) {
            return delegate.directBuffer(initialCapacity);
        }

        @Override
        public ByteBuf directBuffer(int initialCapacity, int maxCapacity) {
            return delegate.directBuffer(initialCapacity, maxCapacity);
        }

        @Override
        public CompositeByteBuf compositeBuffer() {
            return delegate.compositeBuffer();
        }

        @Override
        public CompositeByteBuf compositeBuffer(int maxNumComponents) {
            return delegate.compositeBuffer(maxNumComponents);
        }

        @Override
        public CompositeByteBuf compositeHeapBuffer() {
            return delegate.compositeHeapBuffer();
        }

        @Override
        public CompositeByteBuf compositeHeapBuffer(int maxNumComponents) {
            return delegate.compositeHeapBuffer(maxNumComponents);
        }

        @Override
        public CompositeByteBuf compositeDirectBuffer() {
            return delegate.compositeDirectBuffer();
        }

        @Override
        public CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
            return delegate.compositeDirectBuffer(maxNumComponents);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return delegate.isDirectBufferPooled();
        }

        @Override
        public int calculateNewCapacity(int minNewCapacity, int maxCapacity) {
            return delegate.calculateNewCapacity(minNewCapacity, maxCapacity);
        }
    }
}
