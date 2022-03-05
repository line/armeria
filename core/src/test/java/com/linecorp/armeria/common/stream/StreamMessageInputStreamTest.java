/*
 * Copyright 2022 LINE Corporation
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Flux;

class StreamMessageInputStreamTest {

    @Test
    void readStrings() throws Exception {
        final StreamMessage<String> streamMessage = StreamMessage.of("foo", "bar", "baz");
        final InputStream inputStream = streamMessage.asInputStream(x -> HttpData.wrap(x.getBytes()));
        final byte[] expected = ImmutableList.of("foo", "bar", "baz")
                                             .stream()
                                             .map(String::getBytes)
                                             .reduce(Bytes::concat).get();

        final ByteBuf result = Unpooled.buffer();
        int read;
        while ((read = inputStream.read()) != -1) {
            result.writeByte(read);
        }

        final int readableBytes = result.readableBytes();
        final byte[] actual = new byte[readableBytes];
        for (int i = 0; i < readableBytes; i++) {
            actual[i] = result.readByte();
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(inputStream.available()).isZero();
    }

    @Test
    void readIntegers() throws Exception {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        final InputStream inputStream = streamMessage
                .asInputStream(x -> HttpData.wrap(x.toString().getBytes()));
        final byte[] expected = ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                                             .stream()
                                             .map(x -> x.toString().getBytes())
                                             .reduce(Bytes::concat).get();

        final ByteBuf result = Unpooled.buffer();
        int read;
        while ((read = inputStream.read()) != -1) {
            result.writeByte(read);
        }

        final int readableBytes = result.readableBytes();
        final byte[] actual = new byte[readableBytes];
        for (int i = 0; i < readableBytes; i++) {
            actual[i] = result.readByte();
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(inputStream.available()).isZero();
    }

    @Test
    void readWithStreamMessageOperators() throws Exception {
        final StreamMessage<Integer> streamMessage = StreamMessage
                .of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                .filter(x -> x % 2 == 0)
                .map(x -> x + 10); // 12, 14, 16, 18, 20
        final InputStream inputStream = streamMessage
                .asInputStream(x -> HttpData.wrap(x.toString().getBytes()));
        final byte[] expected = ImmutableList.of(12, 14, 16, 18, 20)
                                             .stream()
                                             .map(x -> x.toString().getBytes())
                                             .reduce(Bytes::concat).get();

        final ByteBuf result = Unpooled.buffer();
        int read;
        while ((read = inputStream.read()) != -1) {
            result.writeByte(read);
        }

        final int readableBytes = result.readableBytes();
        final byte[] actual = new byte[readableBytes];
        for (int i = 0; i < readableBytes; i++) {
            actual[i] = result.readByte();
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(inputStream.available()).isZero();
    }

    @Test
    void readWithOffset() throws Exception {
        final StreamMessage<byte[]> streamMessage = StreamMessage.of(new byte[] {1, 2, 3, 4, 5});
        final InputStream inputStream = streamMessage.asInputStream(HttpData::wrap);

        final byte[] result = new byte[5];
        final int len = inputStream.read(result, 1, 3);

        assertThat(len).isEqualTo(3);
        assertThat(result).isEqualTo(new byte[] { 0, 1, 2, 3, 0});
        assertThat(inputStream.available()).isEqualTo(2);
    }

    @Test
    void readByMultipleThreads_allThreadsShouldWaitSubscribed() throws Exception {
        final CountDownLatch latch = new CountDownLatch(10);
        final Publisher<Integer> publisher = Flux
                .range(1, 10)
                .doOnSubscribe(subscription -> {
                    try {
                        latch.await(); // to ensure that all threads are waiting onSubscribe()
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                });
        final StreamMessage<Integer> streamMessage = new PublisherBasedStreamMessage<>(publisher);
        final InputStream inputStream = streamMessage
                .asInputStream(x -> HttpData.wrap(x.toString().getBytes()));

        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final Thread t = new Thread(() -> assertDoesNotThrow(() -> {
                latch.countDown();
                inputStream.read();
            }));
            threads.add(t);
            t.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    @Test
    void close() throws Exception {
        final Publisher<Integer> publisher = Flux.range(1, 10);
        final StreamMessage<Integer> streamMessage = new PublisherBasedStreamMessage<>(publisher);
        final InputStream inputStream = streamMessage
                .asInputStream(x -> HttpData.wrap(x.toString().getBytes()));
        final byte[] expected = ImmutableList.of(1, 2, 3, 4, 5)
                                             .stream()
                                             .map(x -> x.toString().getBytes())
                                             .reduce(Bytes::concat).get();

        final ByteBuf result = Unpooled.buffer();
        int read;
        while ((read = inputStream.read()) != -1) {
            if (result.readableBytes() == 5) {
                inputStream.close();
                break;
            }
            result.writeByte(read);
        }

        final int readableBytes = result.readableBytes();
        final byte[] actual = new byte[readableBytes];
        for (int i = 0; i < readableBytes; i++) {
            actual[i] = result.readByte();
        }
        assertThat(actual).isEqualTo(expected);
        assertDoesNotThrow(inputStream::close);
        assertThatThrownBy(inputStream::read).isInstanceOf(IOException.class)
                                             .hasMessage("Stream closed");
        assertThatThrownBy(inputStream::available).isInstanceOf(IOException.class)
                                                  .hasMessage("Stream closed");
    }

    @Test
    void closeBeforeRead() throws IOException {
        final StreamMessage<String> streamMessage = StreamMessage.of("foo", "bar", "baz");
        final InputStream inputStream = streamMessage.asInputStream(x -> HttpData.wrap(x.getBytes()));
        assertThat(inputStream.available()).isZero();

        assertDoesNotThrow(inputStream::close);
        assertThatThrownBy(inputStream::read).isInstanceOf(IOException.class)
                                             .hasMessage("Stream closed");
        assertThatThrownBy(inputStream::available).isInstanceOf(IOException.class)
                                                  .hasMessage("Stream closed");
    }

    @Test
    void closeMultipleTimes() throws IOException {
        final StreamMessage<String> streamMessage = StreamMessage.of("foo", "bar", "baz");
        final InputStream inputStream = streamMessage.asInputStream(x -> HttpData.wrap(x.getBytes()));
        assertThat(inputStream.read()).isNotEqualTo(-1);
        assertThat(inputStream.available()).isGreaterThan(0);

        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(inputStream::close);
            assertThatThrownBy(inputStream::read).isInstanceOf(IOException.class)
                                                 .hasMessage("Stream closed");
            assertThatThrownBy(inputStream::available).isInstanceOf(IOException.class)
                                                      .hasMessage("Stream closed");
        }
    }

    @Test
    void available() throws Exception {
        final StreamMessage<byte[]> streamMessage = StreamMessage.of(new byte[] {1, 2, 3, 4, 5});
        final InputStream inputStream = streamMessage.asInputStream(HttpData::wrap);
        final byte[] expected = {1, 2, 3, 4};
        assertThat(inputStream.available()).isZero();

        final ByteBuf result = Unpooled.buffer();
        for (int i = 0; i < 4; i++) {
            result.writeByte(inputStream.read());
        }

        final int readableBytes = result.readableBytes();
        final byte[] actual = new byte[readableBytes];
        for (int i = 0; i < readableBytes; i++) {
            actual[i] = result.readByte();
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(inputStream.available()).isEqualTo(1);

        final int last = inputStream.read();
        assertThat(last).isEqualTo(5);
        assertThat(inputStream.available()).isZero();
        assertThat(inputStream.read()).isEqualTo(-1);
    }

    @Test
    void streamMessage_aborted() throws Exception {
        final Publisher<Integer> publisher = Flux.range(1, 10);
        final StreamMessage<Integer> streamMessage = new PublisherBasedStreamMessage<>(publisher);
        final StreamMessage<Integer> aborted = streamMessage
                .peek(x -> {
                    if (x == 6) {
                        streamMessage.abort();
                    }
                });
        final InputStream inputStream = aborted.asInputStream(x -> HttpData.wrap(x.toString().getBytes()));
        final byte[] expected = ImmutableList.of(1, 2, 3, 4, 5)
                                             .stream()
                                             .map(x -> x.toString().getBytes())
                                             .reduce(Bytes::concat).get();

        final ByteBuf result = Unpooled.buffer();
        int read;
        while ((read = inputStream.read()) != -1) {
            result.writeByte(read);
        }

        final int readableBytes = result.readableBytes();
        final byte[] actual = new byte[readableBytes];
        for (int i = 0; i < readableBytes; i++) {
            actual[i] = result.readByte();
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(inputStream.available()).isZero();
        assertThat(inputStream.read()).isEqualTo(-1);
    }

    @Test
    void streamMessage_error_thrown() throws Exception {
        final Publisher<Integer> publisher = Flux.range(1, 10);
        final StreamMessage<Integer> streamMessage = new PublisherBasedStreamMessage<>(publisher)
                .peek(x -> {
                    if (x == 6) {
                        throw new RuntimeException();
                    }
                });
        final InputStream inputStream = streamMessage
                .asInputStream(x -> HttpData.wrap(x.toString().getBytes()));
        final byte[] expected = ImmutableList.of(1, 2, 3, 4, 5)
                                             .stream()
                                             .map(x -> x.toString().getBytes())
                                             .reduce(Bytes::concat).get();

        final ByteBuf result = Unpooled.buffer();
        int read;
        while ((read = inputStream.read()) != -1) {
            result.writeByte(read);
        }

        final int readableBytes = result.readableBytes();
        final byte[] actual = new byte[readableBytes];
        for (int i = 0; i < readableBytes; i++) {
            actual[i] = result.readByte();
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(inputStream.available()).isZero();
        assertThat(inputStream.read()).isEqualTo(-1);
    }
}
