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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;

import reactor.test.StepVerifier;

class ByteStreamMessageOutputStreamTest {

    @Test
    void write() {
        final ByteStreamMessage byteStreamMessage = StreamMessage.fromOutputStream(os -> {
            try (Closeable ignored = os) {
                for (byte b : "abcde".getBytes()) {
                    os.write(b);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.ofUtf8("a"))
                    .expectNext(HttpData.ofUtf8("b"))
                    .expectNext(HttpData.ofUtf8("c"))
                    .expectNext(HttpData.ofUtf8("d"))
                    .expectNext(HttpData.ofUtf8("e"))
                    .verifyComplete();
    }

    @Test
    void writeIntegers() {
        final ByteStreamMessage byteStreamMessage = newByteStreamMessage();

        StepVerifier.create(byteStreamMessage)
                    .expectNext(httpData(0), httpData(1), httpData(2), httpData(3), httpData(4))
                    .verifyComplete();
    }

    @Test
    void writeOffset() {
        final ByteStreamMessage byteStreamMessage = StreamMessage.fromOutputStream(os -> {
            try (Closeable ignored = os) {
                final byte[] bytes = "_foobarbaz_".getBytes();
                os.write(bytes, 1, bytes.length - 2);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.ofUtf8("foobarbaz"))
                    .verifyComplete();
    }

    @Test
    void writeWithStreamMessage() {
        final StreamMessage<HttpData> streamMessage = StreamMessage
                .of(10, 11, 12, 13, 14)
                .map(ByteStreamMessageOutputStreamTest::httpData);
        final ByteStreamMessage byteStreamMessage = newByteStreamMessage();
        final StreamMessage<HttpData> concat = StreamMessage.concat(streamMessage, byteStreamMessage);

        StepVerifier.create(concat)
                    .expectNext(httpData(10), httpData(11), httpData(12), httpData(13), httpData(14))
                    .expectNext(httpData(0), httpData(1), httpData(2), httpData(3), httpData(4))
                    .verifyComplete();
    }

    @Test
    void blockingWrite() throws InterruptedException {
        final AtomicInteger count = new AtomicInteger();
        final ByteStreamMessage byteStreamMessage = StreamMessage.fromOutputStream(os -> {
            try (Closeable ignored = os) {
                for (int i = 0; i < 3; i++) {
                    os.write(i);
                    count.incrementAndGet();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(httpData(0))
                    .thenAwait(Duration.ofSeconds(3))
                    .then(() -> await().untilAtomic(count, Matchers.is(1)))
                    .thenRequest(1)
                    .expectNext(httpData(1))
                    .thenAwait(Duration.ofSeconds(3))
                    .then(() -> await().untilAtomic(count, Matchers.is(2)))
                    .thenRequest(1)
                    .expectNext(httpData(2))
                    .verifyComplete();
    }

    @Test
    void writeAfterClosed() {
        final ByteStreamMessage byteStreamMessage = StreamMessage.fromOutputStream(os -> {
            try (Closeable ignored = os) {
                for (int i = 0; i < 5; i++) {
                    os.write(i);
                }
                os.close();

                assertThatThrownBy(() -> os.write(0))
                        .isInstanceOf(IOException.class)
                        .hasMessage("Stream closed");
                assertThatThrownBy(() -> os.write(new byte[] { 0 }, 0, 1))
                        .isInstanceOf(IOException.class)
                        .hasMessage("Stream closed");
                assertThatCode(os::close).doesNotThrowAnyException();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        StepVerifier.create(byteStreamMessage)
                    .expectNext(httpData(0), httpData(1), httpData(2), httpData(3), httpData(4))
                    .verifyComplete();
    }

    @Test
    void writeAfterStreamClosed() throws InterruptedException {
        final CountDownLatch abortWait = new CountDownLatch(1);
        final CountDownLatch wait = new CountDownLatch(1);
        final CountDownLatch end = new CountDownLatch(1);
        final ByteStreamMessage byteStreamMessage = StreamMessage.fromOutputStream(os -> {
            try (Closeable ignored = os) {
                for (int i = 0; i < 5; i++) {
                    if (i < 2) {
                        os.write(i);
                    } else {
                        abortWait.countDown();
                        wait.await();
                        assertThatThrownBy(() -> os.write(0))
                                .isInstanceOf(IOException.class)
                                .hasMessage("Stream closed");
                    }
                }
                assertThatCode(os::close).doesNotThrowAnyException();
                end.countDown();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        StepVerifier.create(byteStreamMessage, 2)
                    .expectNext(httpData(0), httpData(1))
                    .then(() -> {
                        try {
                            abortWait.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .then(byteStreamMessage::abort)
                    .then(() -> {
                        // Wait for the abortion to be completed.
                        assertThatThrownBy(() -> byteStreamMessage.whenComplete().join())
                                .isInstanceOf(CompletionException.class)
                                .hasCauseInstanceOf(AbortedStreamException.class);
                    })
                    .then(wait::countDown)
                    .verifyError(AbortedStreamException.class);
        end.await();
    }

    @Test
    void writeAfterAborted() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(10, 11, 12, 13, 14);
        final StreamMessage<HttpData> aborted = streamMessage
                .peek(x -> {
                    if (x == 13) {
                        streamMessage.abort();
                        streamMessage.whenComplete().join();
                    }
                })
                .map(ByteStreamMessageOutputStreamTest::httpData);
        final ByteStreamMessage byteStreamMessage = newByteStreamMessage();
        final StreamMessage<HttpData> concat = StreamMessage.concat(aborted, byteStreamMessage);

        StepVerifier.create(concat)
                    .expectNext(httpData(10), httpData(11), httpData(12))
                    .verifyError(AbortedStreamException.class);
    }

    @Test
    void write_error_thrown() {
        final ByteStreamMessage byteStreamMessage = StreamMessage.fromOutputStream(os -> {
            try {
                for (int i = 0; i < 5; i++) {
                    if (i < 3) {
                        os.write(i);
                    } else {
                        throw new RuntimeException();
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        StepVerifier.create(byteStreamMessage)
                    .expectNext(httpData(0), httpData(1), httpData(2))
                    .verifyError(RuntimeException.class);
    }

    @Test
    void range_zeroLength() {
        final ByteStreamMessage byteStreamMessage = newByteStreamMessage();

        assertThatThrownBy(() -> byteStreamMessage.range(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length: 0 (expected: > 0)");
    }

    @Test
    void skip1() {
        final ByteStreamMessage byteStreamMessage = newByteStreamMessage()
                .range(1, Long.MAX_VALUE);

        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(httpData(1))
                    .thenRequest(2)
                    .expectNext(httpData(2), httpData(3))
                    .thenRequest(1)
                    .expectNext(httpData(4))
                    .verifyComplete();
    }

    @Test
    void skip1_take2() {
        final ByteStreamMessage byteStreamMessage = newByteStreamMessage()
                .range(1, 2);

        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(httpData(1))
                    .thenRequest(1)
                    .expectNext(httpData(2))
                    .verifyComplete();
    }

    @Test
    void skip0_take3() {
        final ByteStreamMessage byteStreamMessage = newByteStreamMessage()
                .range(0, 3);

        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(httpData(0))
                    .thenRequest(1)
                    .expectNext(httpData(1))
                    .thenRequest(1)
                    .expectNext(httpData(2))
                    .verifyComplete();
    }

    @Test
    void skipAll() {
        final ByteStreamMessage byteStreamMessage = newByteStreamMessage()
                .range(5, Integer.MAX_VALUE);

        StepVerifier.create(byteStreamMessage)
                    .verifyComplete();
    }

    @Test
    void skipMost() {
        final ByteStreamMessage byteStreamMessage = newByteStreamMessage()
                .range(4, Integer.MAX_VALUE);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(httpData(4))
                    .verifyComplete();
    }

    @Test
    void collectBytes() {
        final ByteStreamMessage byteStreamMessage = newByteStreamMessage();
        final byte[] bytes = byteStreamMessage.collectBytes().join();
        assertThat(bytes).isEqualTo(new byte[] { 0, 1, 2, 3, 4 });
    }

    private static HttpData httpData(int b) {
        return HttpData.copyOf(new byte[] { (byte) b });
    }

    private static ByteStreamMessage newByteStreamMessage() {
        return StreamMessage.fromOutputStream(os -> {
            try (Closeable ignored = os) {
                for (int i = 0; i < 5; i++) {
                    os.write(i);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
