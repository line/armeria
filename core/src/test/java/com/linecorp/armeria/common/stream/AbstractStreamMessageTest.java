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
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.testing.common.EventLoopRule;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;

@SuppressWarnings("unchecked")  // Allow using the same tests for writers and non-writers
public abstract class AbstractStreamMessageTest {

    static final List<Integer> TEN_INTEGERS = IntStream.range(0, 10).boxed().collect(toImmutableList());

    @ClassRule
    public static final EventLoopRule eventLoop = new EventLoopRule();

    abstract <T> StreamMessage<T> newStream(List<T> inputs);

    List<Integer> streamValues() {
        return TEN_INTEGERS;
    }

    private List<Integer> result;
    private volatile boolean completed;
    private volatile Throwable error;

    @Before
    public void reset() {
        result = new ArrayList<>();
        completed = false;
    }

    @Test
    public void full_writeFirst() throws Exception {
        final StreamMessage<Integer> stream = newStream(streamValues());
        writeTenIntegers(stream);
        stream.subscribe(new ResultCollectingSubscriber() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }
        });
        assertSuccess();
    }

    @Test
    public void full_writeAfter() throws Exception {
        final StreamMessage<Integer> stream = newStream(streamValues());
        stream.subscribe(new ResultCollectingSubscriber() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }
        });
        writeTenIntegers(stream);
        assertSuccess();
    }

    // Verifies that re-entrancy into onNext, e.g. calling onNext -> request -> onNext, is not allowed, as per
    // reactive streams spec 3.03. If it were allowed, the order of processing would be incorrect and the test
    // would fail.
    @Test
    public void flowControlled_writeThenDemandThenProcess() throws Exception {
        final StreamMessage<Integer> stream = newStream(streamValues());
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
        assertSuccess();
    }

    @Test
    public void flowControlled_writeThenDemandThenProcess_eventLoop() throws Exception {
        final StreamMessage<Integer> stream = newStream(streamValues());
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
        assertSuccess();
    }

    @Test
    public void flowControlled_writeThenProcessThenDemand() throws Exception {
        final StreamMessage<Integer> stream = newStream(streamValues());
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
        assertSuccess();
    }

    @Test
    public void releaseOnConsumption_ByteBuf() throws Exception {
        final ByteBuf buf = newPooledBuffer();
        final StreamMessage<ByteBuf> stream = newStream(ImmutableList.of(buf));

        if (stream instanceof StreamWriter) {
            ((StreamWriter<ByteBuf>) stream).write(buf);
            ((StreamWriter<?>) stream).close();
        }
        assertThat(buf.refCnt()).isEqualTo(1);

        stream.subscribe(new Subscriber<ByteBuf>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBuf o) {
                assertThat(o).isNotSameAs(buf);
                assertThat(o).isInstanceOf(UnpooledHeapByteBuf.class);
                assertThat(o.refCnt()).isEqualTo(1);
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

    @Test
    public void releaseOnConsumption_HttpData() throws Exception {
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), false);
        final StreamMessage<ByteBufHolder> stream = newStream(ImmutableList.of(data));

        if (stream instanceof StreamWriter) {
            ((StreamWriter<ByteBufHolder>) stream).write(data);
            ((StreamWriter<?>) stream).close();
        }
        assertThat(data.refCnt()).isEqualTo(1);

        stream.subscribe(new Subscriber<ByteBufHolder>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBufHolder o) {
                assertThat(o).isNotSameAs(data);
                assertThat(o).isInstanceOf(ByteBufHttpData.class);
                assertThat(o.content()).isInstanceOf(UnpooledHeapByteBuf.class);
                assertThat(o.refCnt()).isEqualTo(1);
                assertThat(data.refCnt()).isZero();
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

    @Test
    public void releaseWithZeroDemand() {
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
        final StreamMessage<Object> stream = newStream(ImmutableList.of(data));
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
                fail();
            }

            @Override
            public void onError(Throwable throwable) {
                fail();
            }

            @Override
            public void onComplete() {
                fail();
            }
        }, true);

        await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
        await().untilAsserted(() -> assertThat(data.refCnt()).isZero());
    }

    @Test
    public void releaseWithZeroDemandAndClosedStream() {
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
        final StreamMessage<Object> stream = newStream(ImmutableList.of(data));
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
                fail();
            }

            @Override
            public void onError(Throwable throwable) {
                fail();
            }

            @Override
            public void onComplete() {
                fail();
            }
        }, true);

        await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
        await().untilAsserted(() -> assertThat(data.refCnt()).isZero());
    }

    private void assertSuccess() {
        await().untilAsserted(() -> assertThat(completed).isTrue());
        assertThat(error).isNull();
        assertThat(result).containsExactlyElementsOf(streamValues());
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

    protected static ByteBuf newPooledBuffer() {
        return PooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
    }

    private void writeTenIntegers(StreamMessage<Integer> stream) {
        if (stream instanceof StreamWriter) {
            final StreamWriter<Integer> writer = (StreamWriter<Integer>) stream;
            streamValues().forEach(writer::write);
            writer.close();
        }
    }
}
