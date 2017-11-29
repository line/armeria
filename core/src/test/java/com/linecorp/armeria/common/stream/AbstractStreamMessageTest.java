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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

public abstract class AbstractStreamMessageTest {

    private static final List<Integer> TEN_INTEGERS = IntStream.range(0, 10).boxed().collect(toImmutableList());

    private static EventLoop eventLoop;

    @BeforeClass
    public static void startEventLoop() {
        eventLoop = new DefaultEventLoop();
    }

    @AfterClass
    public static void stopEventLoop() {
        eventLoop.shutdownGracefully().syncUninterruptibly();
    }

    EventLoop eventLoop() {
        return eventLoop;
    }

    abstract <T> StreamMessageAndWriter<T> newStream(List<T> inputs);

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
        StreamMessageAndWriter<Integer> stream = newStream(TEN_INTEGERS);
        TEN_INTEGERS.forEach(stream::write);
        stream.close();
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
        StreamMessageAndWriter<Integer> stream = newStream(TEN_INTEGERS);
        stream.subscribe(new ResultCollectingSubscriber() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }
        });
        TEN_INTEGERS.forEach(stream::write);
        stream.close();
        assertSuccess();
    }

    // Verifies that re-entrancy into onNext, e.g. calling onNext -> request -> onNext, is not allowed, as per
    // reactive streams spec 3.03. If it were allowed, the order of processing would be incorrect and the test
    // would fail.
    @Test
    public void flowControlled_writeThenDemandThenProcess() throws Exception {
        StreamMessageAndWriter<Integer> stream = newStream(TEN_INTEGERS);
        TEN_INTEGERS.forEach(stream::write);
        stream.close();
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
        StreamMessageAndWriter<Integer> stream = newStream(TEN_INTEGERS);
        TEN_INTEGERS.forEach(stream::write);
        stream.close();
        eventLoop().submit(
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
                        }, eventLoop())).syncUninterruptibly();
        assertSuccess();
    }

    @Test
    public void flowControlled_writeThenProcessThenDemand() throws Exception {
        StreamMessageAndWriter<Integer> stream = newStream(TEN_INTEGERS);
        TEN_INTEGERS.forEach(stream::write);
        stream.close();
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

    /**
     * Makes sure {@link Subscriber#onComplete()} is always invoked after
     * {@link Subscriber#onSubscribe(Subscription)} even if
     * {@link StreamMessage#subscribe(Subscriber, Executor)} is called from non-{@link EventLoop}.
     */
    @Test
    public void onSubscribeBeforeOnComplete() throws Exception {
        final BlockingQueue<String> queue = new LinkedTransferQueue<>();
        // Repeat to increase the chance of reproduction.
        for (int i = 0; i < 8192; i++) {
            StreamMessageAndWriter<Integer> stream = newStream(TEN_INTEGERS);
            eventLoop().execute(stream::close);
            stream.subscribe(new Subscriber<Object>() {
                @Override
                public void onSubscribe(Subscription s) {
                    queue.add("onSubscribe");
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Object o) {
                    queue.add("onNext");
                }

                @Override
                public void onError(Throwable t) {
                    queue.add("onError");
                }

                @Override
                public void onComplete() {
                    queue.add("onComplete");
                }
            }, eventLoop());

            assertThat(queue.poll(5, TimeUnit.SECONDS)).isEqualTo("onSubscribe");
            assertThat(queue.poll(5, TimeUnit.SECONDS)).isEqualTo("onComplete");
        }
    }

    @Test
    public void releaseOnConsumption_ByteBuf() throws Exception {
        final ByteBuf buf = newPooledBuffer();
        StreamMessageAndWriter<ByteBuf> stream = newStream(ImmutableList.of(buf));

        assertThat(stream.write(buf)).isTrue();
        stream.close();
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
        StreamMessageAndWriter<ByteBufHolder> stream = newStream(ImmutableList.of(data));

        assertThat(stream.write(data)).isTrue();
        stream.close();
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
    public void rejectReferenceCounted() {
        AbstractReferenceCounted item = new AbstractReferenceCounted() {
            @Override
            protected void deallocate() {}

            @Override
            public ReferenceCounted touch(Object hint) {
                return this;
            }
        };
        StreamMessageAndWriter<Object> stream = newStream(ImmutableList.of(item));
        assertThatThrownBy(() -> stream.write(item)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void releaseWhenWritingToClosedStream_ByteBuf() {
        StreamMessageAndWriter<Object> stream = newStream(ImmutableList.of());
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
        stream.close();

        await().untilAsserted(() -> assertThat(stream.write(buf)).isFalse());
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    public void releaseWhenWritingToClosedStream_HttpData() {
        StreamMessageAndWriter<Object> stream = newStream(ImmutableList.of());
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
        stream.close();

        await().untilAsserted(() -> assertThat(stream.write(data)).isFalse());
        assertThat(data.refCnt()).isZero();
    }

    @Test
    public void releaseWithZeroDemand() {
        final ByteBufHttpData data = new ByteBufHttpData(newPooledBuffer(), true);
        StreamMessageAndWriter<Object> stream = newStream(ImmutableList.of(data));
        stream.write(data);
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
        StreamMessageAndWriter<Object> stream = newStream(ImmutableList.of(data));
        stream.write(data);
        stream.close();

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
        assertThat(result).containsExactlyElementsOf(TEN_INTEGERS);
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

    private static ByteBuf newPooledBuffer() {
        return PooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
    }
}
