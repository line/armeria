/*
 * Copyright 2017 LINE Corporation
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
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

class DefaultStreamMessageTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    /**
     * Makes sure {@link Subscriber#onComplete()} is always invoked after
     * {@link Subscriber#onSubscribe(Subscription)} even if
     * {@link StreamMessage#subscribe(Subscriber, EventExecutor, SubscriptionOption...)}
     * is called from non-{@link EventLoop}.
     */
    @Test
    void onSubscribeBeforeOnComplete() throws Exception {
        final BlockingQueue<String> queue = new LinkedTransferQueue<>();
        // Repeat to increase the chance of reproduction.
        for (int i = 0; i < 8192; i++) {
            final StreamMessageAndWriter<Integer> stream = new DefaultStreamMessage<>();
            eventLoop.get().execute(stream::close);
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
            }, eventLoop.get());

            assertThat(queue.poll(5, TimeUnit.SECONDS)).isEqualTo("onSubscribe");
            assertThat(queue.poll(5, TimeUnit.SECONDS)).isEqualTo("onComplete");
        }
    }

    @Test
    void releaseWhenWritingToClosedStream_HttpData() {
        final StreamMessageAndWriter<HttpData> stream = new DefaultStreamMessage<>();
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer().writeByte(0).retain();
        stream.close();

        await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
        assertThat(stream.tryWrite(HttpData.wrap(buf))).isFalse();
        assertThat(buf.refCnt()).isOne();
        assertThatThrownBy(() -> stream.write(HttpData.wrap(buf))).isInstanceOf(ClosedStreamException.class);
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void releaseWhenWritingToClosedStream_HttpData_Supplier() {
        final StreamMessageAndWriter<HttpData> stream = new DefaultStreamMessage<>();
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer().writeByte(0).retain();
        stream.close();

        await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
        assertThat(stream.tryWrite(() -> HttpData.wrap(buf))).isFalse();
        assertThat(buf.refCnt()).isOne();
        assertThatThrownBy(() -> stream.write(() -> HttpData.wrap(buf)))
                .isInstanceOf(ClosedStreamException.class);
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void abortedStreamCallOnCompleteIfNoData() throws InterruptedException {
        final StreamMessageAndWriter<Object> stream = new DefaultStreamMessage<>();
        stream.close();

        final AtomicBoolean onCompleteCalled = new AtomicBoolean();
        stream.subscribe(new Subscriber<Object>() {
            @Override
            public void onSubscribe(Subscription s) {}

            @Override
            public void onNext(Object o) {}

            @Override
            public void onError(Throwable t) {
                fail();
            }

            @Override
            public void onComplete() {
                onCompleteCalled.set(true);
            }
        }, ImmediateEventExecutor.INSTANCE);

        stream.abort();
        assertThat(onCompleteCalled.get()).isTrue();
    }

    @Test
    void abortedStreamCallOnErrorAfterCloseIsCalled() throws InterruptedException {
        final StreamMessageAndWriter<HttpData> stream = new DefaultStreamMessage<>();
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
        stream.write(HttpData.wrap(buf).withEndOfStream());
        stream.close();

        final AtomicReference<Throwable> throwableCaptor = new AtomicReference<>();
        stream.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {}

            @Override
            public void onNext(HttpData o) {}

            @Override
            public void onError(Throwable t) {
                throwableCaptor.set(t);
            }

            @Override
            public void onComplete() {
                fail();
            }
        }, ImmediateEventExecutor.INSTANCE);

        stream.abort();
        assertThat(throwableCaptor.get()).isInstanceOf(AbortedStreamException.class);
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void requestWithNegativeValue() {
        final StreamMessageAndWriter<HttpData> stream = new DefaultStreamMessage<>();
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
        stream.write(HttpData.wrap(buf).withEndOfStream());

        final AtomicBoolean onErrorCalled = new AtomicBoolean();
        stream.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(-1);
            }

            @Override
            public void onNext(HttpData o) {}

            @Override
            public void onError(Throwable t) {
                onErrorCalled.set(true);
            }

            @Override
            public void onComplete() {}
        }, ImmediateEventExecutor.INSTANCE);

        assertThat(onErrorCalled.get()).isTrue();
        assertThat(buf.refCnt()).isZero();
        assertThatThrownBy(() -> stream.whenComplete().get())
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected: > 0");
    }

    @Test
    void shouldCompleteWhenConsumingAllElements() throws InterruptedException {
        final DefaultStreamMessage<String> streamMessage = new DefaultStreamMessage<>();
        streamMessage.write("foo");
        streamMessage.whenConsumed().thenRun(() -> {});
        streamMessage.close();

        final AtomicBoolean completed = new AtomicBoolean();
        streamMessage.subscribe(new Subscriber<String>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(String s) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });
        await().untilTrue(completed);
    }

    @Test
    void closeWhileSubscribing() {
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<DefaultStreamMessage<String>> streamMessageRef = new AtomicReference<>();
        streamMessageRef.set(new DefaultStreamMessage<String>() {
            @Override
            protected void subscribe0(EventExecutor executor, SubscriptionOption[] options) {
                streamMessageRef.get().close();
            }
        });
        streamMessageRef.get().subscribe(new Subscriber<String>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String serviceName) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        }, ImmediateEventExecutor.INSTANCE);
        await().untilTrue(completed);
    }
}
