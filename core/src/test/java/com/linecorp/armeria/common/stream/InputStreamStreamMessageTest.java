/*
 * Copyright 2023 LINE Corporation
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
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import reactor.test.StepVerifier;

class InputStreamStreamMessageTest {

    @Test
    void readIntegers() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }))
                    .verifyComplete();
    }

    @Test
    void readIntegers_builder() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage.builder(inputStream).build();

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }))
                    .verifyComplete();
    }

    @Test
    void readIntegers_bufferSize() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .build();

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3 }))
                    .expectNext(HttpData.wrap(new byte[] { 4, 5, 6 }))
                    .expectNext(HttpData.wrap(new byte[] { 7, 8, 9 }))
                    .expectNext(HttpData.wrap(new byte[] { 10 }))
                    .verifyComplete();
    }

    @Test
    void readIntegers_bufferSize_request() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .build();

        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3 }))
                    .thenRequest(2)
                    .expectNext(HttpData.wrap(new byte[] { 4, 5, 6 }))
                    .expectNext(HttpData.wrap(new byte[] { 7, 8, 9 }))
                    .thenRequest(1)
                    .expectNext(HttpData.wrap(new byte[] { 10 }))
                    .thenRequest(1)
                    .verifyComplete();
    }

    @Test
    void readIntegers_bufferSize_request1() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .build();

        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3 }))
                    .thenRequest(1)
                    .expectNext(HttpData.wrap(new byte[] { 4, 5, 6 }))
                    .thenRequest(1)
                    .expectNext(HttpData.wrap(new byte[] { 7, 8, 9 }))
                    .thenRequest(1)
                    .expectNext(HttpData.wrap(new byte[] { 10 }))
                    .thenRequest(1)
                    .verifyComplete();
    }

    @Test
    void readStrings() {
        final InputStream inputStream = new ByteArrayInputStream("foobarbaz".getBytes());
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap("foobarbaz".getBytes()))
                    .verifyComplete();
    }

    @Test
    void readStrings_bufferSize() {
        final InputStream inputStream = new ByteArrayInputStream("foobarbaz".getBytes());
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .build();

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap("foo".getBytes()))
                    .expectNext(HttpData.wrap("bar".getBytes()))
                    .expectNext(HttpData.wrap("baz".getBytes()))
                    .verifyComplete();
    }

    @Test
    void readStrings_bufferSize_request() {
        final InputStream inputStream = new ByteArrayInputStream("foobarbaz".getBytes());
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .build();

        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.wrap("foo".getBytes()))
                    .thenRequest(2)
                    .expectNext(HttpData.wrap("bar".getBytes()))
                    .expectNext(HttpData.wrap("baz".getBytes()))
                    .thenRequest(1)
                    .verifyComplete();
    }

    @Test
    void readStrings_bufferSize_request1() {
        final InputStream inputStream = new ByteArrayInputStream("foobarbaz".getBytes());
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .build();

        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.wrap("foo".getBytes()))
                    .thenRequest(1)
                    .expectNext(HttpData.wrap("bar".getBytes()))
                    .thenRequest(1)
                    .expectNext(HttpData.wrap("baz".getBytes()))
                    .thenRequest(1)
                    .verifyComplete();
    }

    @Test
    void blockingTaskExecutor() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .executor(CommonPools.blockingTaskExecutor())
                .build();

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3 }))
                    .expectNext(HttpData.wrap(new byte[] { 4, 5, 6 }))
                    .expectNext(HttpData.wrap(new byte[] { 7, 8, 9 }))
                    .expectNext(HttpData.wrap(new byte[] { 10 }))
                    .verifyComplete();
    }

    @Test
    void blockingTaskExecutor_withServiceRequestContext() {
        final ServiceRequestContext sctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream);

        try (SafeCloseable ignored = sctx.push()) {
            StepVerifier.create(byteStreamMessage)
                        .expectNext(HttpData.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }))
                        .verifyComplete();
        }
    }

    @Test
    void nullBlockingTaskExecutor_withClientRequestContext() {
        final ClientRequestContext cctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream);

        try (SafeCloseable ignored = cctx.push()) {
            StepVerifier.create(byteStreamMessage)
                        .expectNext(HttpData.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }))
                        .verifyComplete();
        }
    }

    @Test
    void blockingTaskExecutor_request_readingInputStream() {
        final InputStream bytes = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final CountDownLatch wait = new CountDownLatch(3);
        final InputStream inputStream = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    wait.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return bytes.read();
            }
        };
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .executor(CommonPools.blockingTaskExecutor())
                .build();

        StepVerifier.create(byteStreamMessage, 1)
                    .expectSubscription()
                    .expectNoEvent(Duration.ofSeconds(1))
                    .then(wait::countDown)
                    .thenRequest(1)
                    .expectNoEvent(Duration.ofSeconds(1))
                    .then(wait::countDown)
                    .thenRequest(1)
                    .expectNoEvent(Duration.ofSeconds(1))
                    .then(wait::countDown)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3 }))
                    .expectNext(HttpData.wrap(new byte[] { 4, 5, 6 }))
                    .expectNext(HttpData.wrap(new byte[] { 7, 8, 9 }))
                    .thenRequest(1)
                    .expectNext(HttpData.wrap(new byte[] { 10 }))
                    .thenRequest(1)
                    .verifyComplete();
    }

    @Test
    void range_negativeOffset() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream);

        assertThatThrownBy(() -> byteStreamMessage.range(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset: -1 (expected: >= 0)");
    }

    @Test
    void range_negativeLength() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream);

        assertThatThrownBy(() -> byteStreamMessage.range(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length: -1 (expected: >= 0)");
    }

    @Test
    void range_zeroLength_emptyStream() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream).range(0, 0);

        StepVerifier.create(byteStreamMessage)
                    .verifyComplete();
    }

    @Test
    void range_positiveOffset_zeroLength_emptyStream() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream).range(5, 0);

        StepVerifier.create(byteStreamMessage)
                    .verifyComplete();
    }

    @Test
    void skip0() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .of(inputStream)
                .range(0, Long.MAX_VALUE);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }))
                    .verifyComplete();
    }

    @Test
    void skip0_take3() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .of(inputStream)
                .range(0, 3);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3 }))
                    .verifyComplete();
    }

    @Test
    void skip1() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .of(inputStream)
                .range(1, Long.MAX_VALUE);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 2, 3, 4, 5, 6, 7, 8, 9, 10 }))
                    .verifyComplete();
    }

    @Test
    void skip1_take3() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .of(inputStream)
                .range(1, 3);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 2, 3, 4 }))
                    .verifyComplete();
    }

    @Test
    void skipAll() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .of(inputStream)
                .range(10, Long.MAX_VALUE);

        StepVerifier.create(byteStreamMessage)
                    .verifyComplete();
    }

    @Test
    void skipMost() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .of(inputStream)
                .range(9, Long.MAX_VALUE);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 10 }))
                    .verifyComplete();
    }

    @Test
    void skipBufferSize() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .build()
                .range(5, Long.MAX_VALUE);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 6, 7, 8 }))
                    .expectNext(HttpData.wrap(new byte[] { 9, 10 }))
                    .verifyComplete();
    }

    @Test
    void takeBufferSize() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .build()
                .range(2, Long.MAX_VALUE);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 3, 4, 5 }))
                    .expectNext(HttpData.wrap(new byte[] { 6, 7, 8 }))
                    .expectNext(HttpData.wrap(new byte[] { 9, 10 }))
                    .verifyComplete();
    }

    @Test
    void emptyInputStream() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] {});
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream);

        StepVerifier.create(byteStreamMessage)
                    .verifyComplete();
        assertThat(byteStreamMessage.isEmpty()).isTrue();
        await().untilAsserted(() -> assertThat(byteStreamMessage.isOpen()).isFalse());
    }

    @Test
    void closedInputStream() throws IOException {
        final StreamMessage<String> streamMessage = StreamMessage.of("foo", "bar", "baz");
        final InputStream inputStream = streamMessage.toInputStream(x -> HttpData.wrap(x.getBytes()));
        assertDoesNotThrow(inputStream::close);
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream);

        assertThatThrownBy(() -> byteStreamMessage.subscribe().join())
                .hasCauseInstanceOf(IOException.class);
        assertThat(byteStreamMessage.isEmpty()).isTrue();
        await().untilAsserted(() -> assertThat(byteStreamMessage.isOpen()).isFalse());
    }

    @Test
    void thrownInputStream() {
        final InputStream bytes = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final InputStream inputStream = new InputStream() {
            @Override
            public int read() throws IOException {
                final int read = bytes.read();
                if (read >= 5) {
                    throw new IOException();
                }
                return read;
            }
        };
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .executor(CommonPools.blockingTaskExecutor())
                .build();

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3 }))
                    .expectNext(HttpData.wrap(new byte[] { 4 }))
                    .verifyError(IOException.class);
        assertThat(byteStreamMessage.isEmpty()).isFalse();
        await().untilAsserted(() -> assertThat(byteStreamMessage.isOpen()).isFalse());
    }

    @Test
    void aborted() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage
                .builder(inputStream)
                .bufferSize(3)
                .executor(CommonPools.blockingTaskExecutor())
                .build();

        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3 }))
                    .then(byteStreamMessage::abort)
                    .verifyError(AbortedStreamException.class);
        await().untilAsserted(() -> assertThat(byteStreamMessage.isOpen()).isFalse());
    }

    @Test
    void subscribedTwice() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }))
                    .verifyComplete();
        StepVerifier.create(byteStreamMessage)
                    .verifyError(IllegalStateException.class);
    }

    @Test
    void abortedBeforeSubscribed() {
        final InputStream inputStream = new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 });
        final ByteStreamMessage byteStreamMessage = StreamMessage.of(inputStream);

        byteStreamMessage.abort();
        await().untilAsserted(() -> assertThat(byteStreamMessage.isOpen()).isFalse());

        StepVerifier.create(byteStreamMessage)
                    .verifyError(AbortedStreamException.class);
    }
}
