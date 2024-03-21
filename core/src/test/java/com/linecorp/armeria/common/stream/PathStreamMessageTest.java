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

import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsWithPooledObjects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

class PathStreamMessageTest {

    private static final Path testFilePath = Paths.get(
            "src/test/resources/testing/core",
            PathStreamMessageTest.class.getSimpleName(),
            "test.txt");

    @ArgumentsSource(SubscriptionOptionsProvider.class)
    @ParameterizedTest
    void readFile(SubscriptionOption[] options) {
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath)
                                                         .bufferSize(12)
                                                         .build();
        final AtomicBoolean completed = new AtomicBoolean();
        final StringBuilder stringBuilder = new StringBuilder();
        publisher.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                final String str = httpData.toStringUtf8();
                assertThat(str.length()).isLessThanOrEqualTo(12);

                assertThat(httpData.isPooled()).isEqualTo(containsWithPooledObjects(options));
                httpData.close();
                stringBuilder.append(str);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        }, options);

        await().untilTrue(completed);
        assertThat(stringBuilder.toString())
                .isEqualTo("A1234567890\nB1234567890\nC1234567890\nD1234567890\nE1234567890\n");
    }

    @CsvSource({ "1", "12", "128" })
    @ParameterizedTest
    void differentBufferSize(int bufferSize) {
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(bufferSize).build();

        final StringBuilder stringBuilder = new StringBuilder();
        final AtomicBoolean completed = new AtomicBoolean();
        publisher.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                final String str = httpData.toStringUtf8();
                assertThat(str.length()).isLessThanOrEqualTo(bufferSize);
                stringBuilder.append(str);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        await().untilTrue(completed);
        assertThat(stringBuilder.toString())
                .isEqualTo("A1234567890\nB1234567890\nC1234567890\nD1234567890\nE1234567890\n");
    }

    @CsvSource({
            "0, 1000, A1234567890\\nB1234567890\\nC1234567890\\nD1234567890\\nE1234567890\\n, 100",
            "0, 1000, A1234567890\\nB1234567890\\nC1234567890\\nD1234567890\\nE1234567890\\n, 3",
            "0, 10, A123456789, 100",
            "0, 10, A123456789, 2",
            "10, 1000, 0\\nB1234567890\\nC1234567890\\nD1234567890\\nE1234567890\\n, 50",
            "10, 1000, 0\\nB1234567890\\nC1234567890\\nD1234567890\\nE1234567890\\n, 1",
            "10, 20, 0\\nB1234567, 2",
            "10, 20, 0\\nB1234567, 1"
    })
    @ParameterizedTest
    void differentPosition(int start, int end, String expected, int bufferSize) {
        expected = expected.replaceAll("\\\\n", "\n");
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(bufferSize).build();
        final int length = end - start;
        publisher.range(start, length);

        final int maxChunkSize = Math.min(Ints.saturatedCast(length), bufferSize);
        final StringBuilder stringBuilder = new StringBuilder();
        final AtomicBoolean completed = new AtomicBoolean();
        publisher.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                assertThat(httpData.length()).isLessThanOrEqualTo(maxChunkSize);
                final String str = httpData.toStringUtf8();
                stringBuilder.append(str);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        await().untilTrue(completed);
        assertThat(stringBuilder.toString()).isEqualTo(expected);
    }

    @Test
    void nonPositiveNumber() {

        assertThatThrownBy(() -> StreamMessage.builder(testFilePath).build().range(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset: -1 (expected: >= 0)");
        assertThatThrownBy(() -> StreamMessage.builder(testFilePath).build().range(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length: 0 (expected: > 0)");

        assertThatThrownBy(() -> StreamMessage.builder(testFilePath).bufferSize(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bufferSize: 0 (expected: > 0)");
    }

    @Test
    void defaultBlockingTaskExecutor_withServiceRequestContext() {
        final ServiceRequestContext sctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(1).build();
        final StringAggregator stringAggregator = new StringAggregator();
        try (SafeCloseable ignored = sctx.push()) {
            publisher.subscribe(stringAggregator);
        }
        final String result = stringAggregator.future.join();
        assertThat(result).isEqualTo("A1234567890\nB1234567890\nC1234567890\nD1234567890\nE1234567890\n");
    }

    @Test
    void nullBlockingTaskExecutor_withClientRequestContext() {
        final ClientRequestContext cctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(1).build();
        final StringAggregator stringAggregator = new StringAggregator();
        try (SafeCloseable ignored = cctx.push()) {
            publisher.subscribe(stringAggregator);
        }
        final String result = stringAggregator.future.join();
        assertThat(result).isEqualTo("A1234567890\nB1234567890\nC1234567890\nD1234567890\nE1234567890\n");
    }

    @Test
    void skip0() {
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(1).build()
                                                         .range(0, Long.MAX_VALUE);
        final StringAggregator stringAggregator = new StringAggregator();

        publisher.subscribe(stringAggregator);
        final String result = stringAggregator.future.join();

        assertThat(result).isEqualTo("A1234567890\nB1234567890\nC1234567890\nD1234567890\nE1234567890\n");
    }

    @Test
    void skip0_take13() {
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(1).build()
                                                         .range(0, 13);
        final StringAggregator stringAggregator = new StringAggregator();

        publisher.subscribe(stringAggregator);
        final String result = stringAggregator.future.join();

        assertThat(result).isEqualTo("A1234567890\nB");
    }

    @Test
    void skip12() {
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(1).build()
                                                         .range(12, Long.MAX_VALUE);
        final StringAggregator stringAggregator = new StringAggregator();

        publisher.subscribe(stringAggregator);
        final String result = stringAggregator.future.join();

        assertThat(result).isEqualTo("B1234567890\nC1234567890\nD1234567890\nE1234567890\n");
    }

    @Test
    void skip12_take13() {
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(1).build()
                                                         .range(12, 13);
        final StringAggregator stringAggregator = new StringAggregator();

        publisher.subscribe(stringAggregator);
        final String result = stringAggregator.future.join();

        assertThat(result).isEqualTo("B1234567890\nC");
    }

    @Test
    void skipAll() {
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(1).build()
                                                         .range(999, Long.MAX_VALUE);
        final StringAggregator stringAggregator = new StringAggregator();

        publisher.subscribe(stringAggregator);
        final String result = stringAggregator.future.join();

        assertThat(result).isEmpty();
    }

    @Test
    void skipMost() {
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(1).build()
                                                         .range(48, Long.MAX_VALUE);
        final StringAggregator stringAggregator = new StringAggregator();

        publisher.subscribe(stringAggregator);
        final String result = stringAggregator.future.join();

        assertThat(result).isEqualTo("E1234567890\n");
    }

    @Test
    void skip1() {
        final ByteStreamMessage publisher = StreamMessage.builder(testFilePath).bufferSize(1).build()
                                                         .range(1, Long.MAX_VALUE);
        final StringAggregator stringAggregator = new StringAggregator();

        publisher.subscribe(stringAggregator);
        final String result = stringAggregator.future.join();

        assertThat(result).isEqualTo("1234567890\nB1234567890\nC1234567890\nD1234567890\nE1234567890\n");
    }

    private static class StringAggregator implements Subscriber<HttpData> {
        private final StringBuilder stringBuilder = new StringBuilder();
        private final CompletableFuture<String> future = new CompletableFuture<>();

        @Override
        public void onSubscribe(Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpData httpData) {
            final String str = httpData.toStringUtf8();
            stringBuilder.append(str);
        }

        @Override
        public void onError(Throwable t) {
            future.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            future.complete(stringBuilder.toString());
        }
    }

    private static class SubscriptionOptionsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(
                    Arguments.of((Object) EMPTY_OPTIONS),
                    Arguments.of((Object) new SubscriptionOption[]{ SubscriptionOption.WITH_POOLED_OBJECTS }));
        }
    }
}
