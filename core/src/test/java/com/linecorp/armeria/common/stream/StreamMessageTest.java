/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import reactor.test.StepVerifier;

class StreamMessageTest {

    private static final List<Integer> TEN_INTEGERS = IntStream.range(0, 10).boxed().collect(toImmutableList());

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    private List<Integer> result;
    private volatile boolean completed;
    private volatile Throwable error;

    @BeforeEach
    void reset() {
        result = new ArrayList<>();
        completed = false;
    }

    @ParameterizedTest
    @ArgumentsSource(StreamProvider.class)
    void full_writeFirst(StreamMessage<Integer> stream, List<Integer> values) {
        writeTenIntegers(stream);
        stream.subscribe(new ResultCollectingSubscriber() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }
        });
        assertSuccess(values);
    }

    @ParameterizedTest
    @ArgumentsSource(StreamProvider.class)
    void full_writeAfter(StreamMessage<Integer> stream, List<Integer> values) {
        stream.subscribe(new ResultCollectingSubscriber() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }
        });
        writeTenIntegers(stream);
        assertSuccess(values);
    }

    // Verifies that re-entrancy into onNext, e.g. calling onNext -> request -> onNext, is not allowed, as per
    // reactive streams spec 3.03. If it were allowed, the order of processing would be incorrect and the test
    // would fail.
    @ParameterizedTest
    @ArgumentsSource(StreamProvider.class)
    void flowControlled_writeThenDemandThenProcess(StreamMessage<Integer> stream, List<Integer> values) {
        writeTenIntegers(stream);
        stream.subscribe(new ResultCollectingSubscriber() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                subscription.request(1);
            }

            @Override
            public void onNext(Integer value) {
                subscription.request(1);
                super.onNext(value);
            }
        });
        assertSuccess(values);
    }

    @ParameterizedTest
    @ArgumentsSource(StreamProvider.class)
    void flowControlled_writeThenDemandThenProcess_eventLoop(StreamMessage<Integer> stream,
                                                             List<Integer> values) {
        writeTenIntegers(stream);
        eventLoop.get().submit(
                () ->
                        stream.subscribe(new ResultCollectingSubscriber() {
                            private Subscription subscription;

                            @Override
                            public void onSubscribe(Subscription s) {
                                subscription = s;
                                subscription.request(1);
                            }

                            @Override
                            public void onNext(Integer value) {
                                subscription.request(1);
                                super.onNext(value);
                            }
                        }, eventLoop.get())).syncUninterruptibly();
        assertSuccess(values);
    }

    @ParameterizedTest
    @ArgumentsSource(StreamProvider.class)
    void flowControlled_writeThenProcessThenDemand(StreamMessage<Integer> stream, List<Integer> values) {
        writeTenIntegers(stream);
        stream.subscribe(new ResultCollectingSubscriber() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                subscription.request(1);
            }

            @Override
            public void onNext(Integer value) {
                super.onNext(value);
                subscription.request(1);
            }
        });
        assertSuccess(values);
    }

    @ParameterizedTest
    @ArgumentsSource(PooledHttpDataStreamProvider.class)
    void releaseOnConsumption_HttpData(HttpData data, ByteBuf buf, StreamMessage<HttpData> stream) {
        if (stream instanceof StreamWriter) {
            ((StreamWriter<HttpData>) stream).write(data);
            ((StreamWriter<?>) stream).close();
        }
        assertThat(data.isPooled()).isTrue();
        assertThat(buf.refCnt()).isOne();

        stream.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(HttpData o) {
                assertThat(o).isNotSameAs(data);
                assertThat(o.isPooled()).isFalse();
                assertThat(buf.refCnt()).isZero();
            }

            @Override
            public void onError(Throwable throwable) {
                Exceptions.throwUnsafely(throwable);
            }

            @Override
            public void onComplete() {
                completed = true;
            }
        });
        await().untilAsserted(() -> assertThat(completed).isTrue());
    }

    @ParameterizedTest
    @ArgumentsSource(PooledHttpDataStreamProvider.class)
    void releaseWithZeroDemand(HttpData data, ByteBuf buf, StreamMessage<HttpData> stream) {
        if (stream instanceof StreamWriter) {
            ((StreamWriter<Object>) stream).write(data);
        }
        stream.subscribe(new Subscriber<Object>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                // Cancel the subscription when the demand is 0.
                subscription.cancel();
            }

            @Override
            public void onNext(Object o) {
                fail("onNext() invoked unexpectedly");
            }

            @Override
            public void onError(Throwable throwable) {
                // This is not called because we didn't specify NOTIFY_CANCELLATION when subscribe.
                fail("onError() invoked unexpectedly");
            }

            @Override
            public void onComplete() {
                fail("onComplete() invoked unexpectedly");
            }
        }, SubscriptionOption.WITH_POOLED_OBJECTS);

        await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
        await().untilAsserted(() -> assertThat(buf.refCnt()).isZero());
    }

    @ParameterizedTest
    @ArgumentsSource(PooledHttpDataStreamProvider.class)
    void releaseWithZeroDemandAndClosedStream(HttpData data, ByteBuf buf, StreamMessage<HttpData> stream) {
        if (stream instanceof StreamWriter) {
            ((StreamWriter<Object>) stream).write(data);
            ((StreamWriter<Object>) stream).close();
        }

        stream.subscribe(new Subscriber<Object>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                // Cancel the subscription when the demand is 0.
                subscription.cancel();
            }

            @Override
            public void onNext(Object o) {
                fail("onNext() invoked unexpectedly");
            }

            @Override
            public void onError(Throwable throwable) {
                // This is not called because we didn't specify NOTIFY_CANCELLATION when subscribe.
                fail("onError() invoked unexpectedly");
            }

            @Override
            public void onComplete() {
                fail("onComplete() invoked unexpectedly");
            }
        }, SubscriptionOption.WITH_POOLED_OBJECTS);

        await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
        await().untilAsserted(() -> assertThat(buf.refCnt()).isZero());
    }

    @Test
    void abortedStream() {
        final Throwable cause = new IllegalStateException("oops");
        final StreamMessage<Object> stream = StreamMessage.aborted(cause);
        StepVerifier.create(stream)
                    .expectErrorMatches(ex -> ex == cause)
                    .verify();
    }

    @Test
    void writeToFile(@TempDir Path tempDir) throws IOException {
        final ByteBuf[] bufs = new ByteBuf[10];
        for (int i = 0; i < 10; i++) {
            bufs[i] = Unpooled.wrappedBuffer(Integer.toString(i).getBytes());
        }
        final byte[] expected = Arrays.stream(bufs)
                                      .map(ByteBuf::array)
                                      .reduce(Bytes::concat).get();

        final StreamMessage<ByteBuf> publisher = StreamMessage.of(bufs);
        final Path destination = tempDir.resolve("foo.bin");
        publisher.writeTo(HttpData::wrap, destination).join();
        final byte[] bytes = Files.readAllBytes(destination);

        assertThat(bytes).isEqualTo(expected);
        for (ByteBuf buf : bufs) {
            assertThat(buf.refCnt()).isZero();
        }
    }

    private static class StreamProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    arguments(new DefaultStreamMessage<>(), TEN_INTEGERS),
                    arguments(StreamMessage.of(), ImmutableList.of()),
                    arguments(StreamMessage.of(0), ImmutableList.of(0)),
                    arguments(StreamMessage.of(0, 1), ImmutableList.of(0, 1)),
                    arguments(StreamMessage.of(TEN_INTEGERS.toArray(new Integer[0])), TEN_INTEGERS));
        }
    }

    private static class PooledHttpDataStreamProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            final ByteBuf defaultBuf = newPooledBuffer();
            final HttpData defaultData = HttpData.wrap(defaultBuf).withEndOfStream();
            final DefaultStreamMessage<HttpData> defaultStream = new DefaultStreamMessage<>();

            final ByteBuf fixedBuf = newPooledBuffer();
            final HttpData fixedData = HttpData.wrap(fixedBuf).withEndOfStream();
            final StreamMessage<HttpData> fixedStream = StreamMessage.of(fixedData);

            return Stream.of(arguments(defaultData, defaultBuf, defaultStream),
                             arguments(fixedData, fixedBuf, fixedStream));
        }
    }

    private void assertSuccess(List<Integer> values) {
        await().untilAsserted(() -> assertThat(completed).isTrue());
        assertThat(error).isNull();
        assertThat(result).containsExactlyElementsOf(values);
    }

    private abstract class ResultCollectingSubscriber implements Subscriber<Integer> {

        @Override
        public void onNext(Integer value) {
            result.add(value);
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }
    }

    static ByteBuf newPooledBuffer() {
        return PooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
    }

    private static void writeTenIntegers(StreamMessage<Integer> stream) {
        if (stream instanceof StreamWriter) {
            final StreamWriter<Integer> writer = (StreamWriter<Integer>) stream;
            TEN_INTEGERS.forEach(writer::write);
            writer.close();
        }
    }
}
