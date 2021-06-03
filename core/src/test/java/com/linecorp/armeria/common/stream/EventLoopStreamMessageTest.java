/*
 * Copyright 2021 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

class EventLoopStreamMessageTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @RegisterExtension
    static final EventLoopExtension subscribeEventLoop = new EventLoopExtension();

    private static final BlockingQueue<Object> queue = new LinkedTransferQueue<>();
    private static final AtomicInteger subscribed = new AtomicInteger();
    private static final AtomicInteger next = new AtomicInteger();
    private static final AtomicInteger completed = new AtomicInteger();
    private static final AtomicInteger error = new AtomicInteger();

    private static class DefaultSubscriber<T> implements Subscriber<T> {
        final EventExecutor executor;

        DefaultSubscriber(EventExecutor executor) {
            this.executor = executor;
        }

        Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
            assertThat(executor.inEventLoop()).isTrue();
            subscription = s;
            subscribed.incrementAndGet();
            queue.offer(s);
        }

        @Override
        public void onNext(T t) {
            assertThat(executor.inEventLoop()).isTrue();
            next.incrementAndGet();
            queue.offer(t);
        }

        @Override
        public void onError(Throwable t) {
            assertThat(executor.inEventLoop()).isTrue();
            error.incrementAndGet();
            queue.offer(t);
        }

        @Override
        public void onComplete() {
            assertThat(executor.inEventLoop()).isTrue();
            completed.incrementAndGet();
        }
    }

    private static class DefaultRequestingSubscriber<T> extends DefaultSubscriber<T> {
        DefaultRequestingSubscriber(EventExecutor executor) {
            super(executor);
        }

        @Override
        public void onSubscribe(Subscription s) {
            super.onSubscribe(s);
            subscription.request(1);
        }

        @Override
        public void onNext(T t) {
            super.onNext(t);
            subscription.request(1);
        }
    }

    ListAppender<ILoggingEvent> appender;

    private static DefaultRequestingSubscriber<Object> DEFAULT_SUBSCRIBER;

    @BeforeEach
    void beforeEach() {
        queue.clear();
        subscribed.set(0);
        next.set(0);
        error.set(0);
        completed.set(0);

        DEFAULT_SUBSCRIBER = new DefaultRequestingSubscriber<>(subscribeEventLoop.get());

        final Logger logger = (Logger) LoggerFactory.getLogger(EventLoopStreamMessage.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @Test
    void testOnSubscribeThrows() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        eventLoop.get().execute(() -> streamMessage.write(1));

        final RuntimeException exception = new RuntimeException();
        streamMessage.subscribe(new DefaultSubscriber<Integer>(subscribeEventLoop.get()) {
            @Override
            public void onSubscribe(Subscription s) {
                super.onSubscribe(s);
                throw exception;
            }
        }, subscribeEventLoop.get());

        await().until(() -> subscribed.get() == 1);
        await().until(() -> error.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isEqualTo(exception);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testSubscribeAndAbort() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());
        await().until(() -> subscribed.get() == 1);

        eventLoop.get().execute(() -> streamMessage.write(1));
        await().until(() -> next.get() == 1);

        streamMessage.abort();
        eventLoop.get().execute(() -> streamMessage.write(2));
        await().until(() -> error.get() == 1);

        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isEqualTo(1);
        assertThat(queue.take()).isInstanceOf(AbortedStreamException.class);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testAbortAndSubscribe() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        streamMessage.abort();

        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());

        await().until(() -> subscribed.get() == 1);
        await().until(() -> error.get() == 1);
        assertThat(queue.take()).isEqualTo(NoopSubscription.get());
        assertThat(queue.take()).isInstanceOf(AbortedStreamException.class);
        assertThat(streamMessage.queue).isEmpty();
        assertThat(streamMessage.subscriber).isInstanceOf(AbortingSubscriber.class);
    }

    @Test
    void testAbortOnErrorCompletes() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        final RuntimeException runtimeException = new RuntimeException();

        streamMessage.subscribe(new DefaultSubscriber<Integer>(subscribeEventLoop.get()) {
            @Override
            public void onError(Throwable t) {
                super.onError(t);
                throw runtimeException;
            }
        }, subscribeEventLoop.get());
        eventLoop.get().execute(streamMessage::abort);

        await().until(() -> subscribed.get() == 1);
        await().until(() -> error.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isInstanceOf(AbortedStreamException.class);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.whenComplete())
                .hasFailedWithThrowableThat()
                .isInstanceOf(CompositeException.class)
                .extracting(t -> ((CompositeException) t).getExceptions())
                .satisfies(throwables -> assertThat(throwables).contains(runtimeException));
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testCancelledStreamCompletes() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());

        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());
        streamMessage.cancel();

        await().until(() -> subscribed.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue).isEmpty();

        streamMessage.whenComplete().handle((result, e) -> {
            assertThat(subscribeEventLoop.get().inEventLoop()).isTrue();
            return null;
        });

        await().until(() -> streamMessage.whenComplete().isDone());
        assertThat(streamMessage.whenComplete())
                .hasFailedWithThrowableThat()
                .isEqualTo(CancelledSubscriptionException.INSTANCE);
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testLateSubscriberOnErrorLogs() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        final RuntimeException runtimeException = new RuntimeException("late subscriber");
        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());
        streamMessage.subscribe(new DefaultSubscriber<Integer>(subscribeEventLoop.get()) {
            @Override
            public void onError(Throwable t) {
                super.onError(t);
                throw runtimeException;
            }
        }, subscribeEventLoop.get());
        eventLoop.get().execute(streamMessage::close);

        await().until(() -> subscribed.get() == 2);
        await().until(() -> completed.get() == 1);
        await().until(() -> error.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isEqualTo(NoopSubscription.get());
        assertThat(queue.take()).isInstanceOf(IllegalStateException.class);
        assertThat(queue).isEmpty();
        assertThat(appender.list).anyMatch(
                iLoggingEvent -> iLoggingEvent.getMessage().contains(
                        "Subscriber should not throw an exception"));
    }

    @Test
    void subscribeAndClose() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());
        eventLoop.get().execute(() -> streamMessage.write(1));
        eventLoop.get().execute(streamMessage::close);
        eventLoop.get().execute(() -> streamMessage.write(2));
        eventLoop.get().execute(() -> streamMessage.write(3));

        await().until(() -> subscribed.get() == 1);
        await().until(() -> next.get() == 1);
        await().until(() -> completed.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isEqualTo(1);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
        assertThat(streamMessage.subscriber).isInstanceOf(NeverInvokedSubscriber.class);
    }

    @Test
    void closeAndSubscribe() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        eventLoop.get().execute(() -> streamMessage.write(1));
        eventLoop.get().execute(streamMessage::close);
        eventLoop.get().execute(() -> streamMessage.write(2));
        eventLoop.get().execute(() -> streamMessage.write(3));

        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());

        await().until(() -> subscribed.get() == 1);
        await().until(() -> completed.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isEqualTo(1);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    private static Stream<Arguments> cancelSubscriptionOptions() {
        return Stream.of(
                Arguments.of(InternalStreamMessageUtil.EMPTY_OPTIONS, false),
                Arguments.of(InternalStreamMessageUtil.CANCELLATION_OPTION, true),
                Arguments.of(InternalStreamMessageUtil.CANCELLATION_AND_POOLED_OPTIONS, true)
        );
    }

    @ParameterizedTest
    @MethodSource("cancelSubscriptionOptions")
    void cancelWithoutNotification(SubscriptionOption[] subscriptionOptions, boolean shouldNotifyCancel)
            throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        eventLoop.get().execute(() -> streamMessage.write(1));

        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        streamMessage.subscribe(new DefaultSubscriber<Integer>(subscribeEventLoop.get()) {
            @Override
            public void onSubscribe(Subscription s) {
                super.onSubscribe(s);
                subscriptionRef.set(s);
                s.request(1);
            }
        }, subscribeEventLoop.get(), subscriptionOptions);

        await().until(() -> next.get() == 1);
        subscriptionRef.get().cancel();

        eventLoop.get().execute(() -> streamMessage.write(2));

        await().until(() -> subscribed.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isEqualTo(1);

        if (shouldNotifyCancel) {
            await().until(() -> error.get() == 1);
            assertThat(queue.take()).isInstanceOf(CancelledSubscriptionException.class);
        }

        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void lateSubscribe() throws InterruptedException {
        final StreamMessageAndWriter<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());
        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());
        eventLoop.get().execute(streamMessage::close);

        await().until(() -> subscribed.get() == 2);
        await().until(() -> completed.get() == 1);
        await().until(() -> error.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isEqualTo(NoopSubscription.get());
        assertThat(queue.take()).isInstanceOf(IllegalStateException.class);
        assertThat(queue).isEmpty();
    }

    @Test
    void testConcurrentSubscribe() throws InterruptedException {
        final StreamMessageAndWriter<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());

        for (int i = 0; i < 10; i++) {
            CompletableFuture.runAsync(() -> streamMessage.subscribe(DEFAULT_SUBSCRIBER,
                                                                     subscribeEventLoop.get()));
        }

        await().until(() -> subscribed.get() == 10);
        eventLoop.get().execute(streamMessage::close);

        await().until(() -> error.get() == 9);
        await().until(() -> completed.get() == 1);
    }

    @Test
    void testSubscribeAndWrite() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());
        eventLoop.get().execute(() -> IntStream.range(0, 10).boxed().forEach(streamMessage::write));
        eventLoop.get().execute(streamMessage::close);

        await().until(() -> subscribed.get() == 1);
        await().until(() -> next.get() == 10);
        await().until(() -> completed.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        for (int i = 0; i < 10; i++) {
            assertThat(queue.take()).isEqualTo(i);
        }
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testWriteAndSubscribe() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        eventLoop.get().execute(() -> IntStream.range(0, 10).boxed().forEach(streamMessage::write));
        eventLoop.get().execute(streamMessage::close);
        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());

        await().until(() -> subscribed.get() == 1);
        await().until(() -> next.get() == 10);
        await().until(() -> completed.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        for (int i = 0; i < 10; i++) {
            assertThat(queue.take()).isEqualTo(i);
        }
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testOnNextThrows() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());

        eventLoop.get().execute(() -> streamMessage.write(1));

        final RuntimeException runtimeException = new RuntimeException();
        streamMessage.subscribe(new DefaultSubscriber<Integer>(subscribeEventLoop.get()) {
            @Override
            public void onSubscribe(Subscription s) {
                super.onSubscribe(s);
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Integer integer) {
                super.onNext(integer);
                throw runtimeException;
            }
        }, subscribeEventLoop.get());

        await().until(() -> subscribed.get() == 1);
        await().until(() -> next.get() == 1);
        await().until(() -> error.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isEqualTo(1);
        assertThat(queue.take()).isEqualTo(runtimeException);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testIsEmpty() throws InterruptedException {
        final StreamMessageAndWriter<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        assertThat(streamMessage.isEmpty()).isFalse();
        assertThat(streamMessage.isOpen()).isTrue();

        eventLoop.get().execute(streamMessage::close);
        await().untilAsserted(() -> assertThat(streamMessage.isOpen()).isFalse());
        await().untilAsserted(() -> assertThat(streamMessage.isEmpty()).isTrue());

        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue).isEmpty();
    }

    private static Stream<Arguments> pooledSubscriptionOptions() {
        return Stream.of(
                Arguments.of(InternalStreamMessageUtil.EMPTY_OPTIONS, getHttpData(false)),
                Arguments.of(InternalStreamMessageUtil.POOLED_OBJECTS, getHttpData(false)),
                Arguments.of(InternalStreamMessageUtil.CANCELLATION_AND_POOLED_OPTIONS, getHttpData(false)),
                Arguments.of(InternalStreamMessageUtil.EMPTY_OPTIONS, getHttpData(true)),
                Arguments.of(InternalStreamMessageUtil.POOLED_OBJECTS, getHttpData(true)),
                Arguments.of(InternalStreamMessageUtil.CANCELLATION_AND_POOLED_OPTIONS, getHttpData(true))
        );
    }

    private static HttpData getHttpData(boolean pooled) {
        if (pooled) {
            final ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
            return HttpData.wrap(byteBuf);
        } else {
            return HttpData.copyOf(new byte[] {1});
        }
    }

    @ParameterizedTest
    @MethodSource("pooledSubscriptionOptions")
    void testOnNextPooledOption(SubscriptionOption[] options, HttpData httpData)
            throws InterruptedException {
        final EventLoopStreamMessage<HttpData> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());

        final ByteBuf byteBuf = httpData.byteBuf();
        assertThat(byteBuf.refCnt()).isOne();

        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get(), options);
        eventLoop.get().execute(() -> streamMessage.write(httpData));
        eventLoop.get().execute(streamMessage::close);

        await().until(() -> subscribed.get() == 1);
        await().until(() -> next.get() == 1);
        await().until(() -> completed.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);

        final HttpData taken = (HttpData) queue.take();
        assertThat(taken).isEqualTo(httpData);

        if (!InternalStreamMessageUtil.containsWithPooledObjects(options) && httpData.isPooled()) {
            assertThat(byteBuf.refCnt()).isEqualTo(0);
            assertThat(taken).isNotSameAs(httpData);
            assertThat(taken.isPooled()).isFalse();
        } else {
            assertThat(byteBuf.refCnt()).isEqualTo(1);
            assertThat(taken).isSameAs(httpData);
            byteBuf.release();
        }

        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("pooledSubscriptionOptions")
    void testDrainPooledOption(SubscriptionOption[] options, HttpData httpData)
            throws InterruptedException {
        final EventLoopStreamMessage<HttpData> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());

        final ByteBuf byteBuf = httpData.byteBuf();
        assertThat(byteBuf.refCnt()).isOne();

        // subscribe without requesting to trigger drain with messages
        streamMessage.subscribe(new DefaultSubscriber<HttpData>(subscribeEventLoop.get()) {
        }, subscribeEventLoop.get(), options);

        eventLoop.get().execute(() -> streamMessage.write(httpData));
        eventLoop.get().execute(streamMessage::abort);

        await().until(() -> subscribed.get() == 1);
        await().until(() -> error.get() == 1);
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isInstanceOf(AbortedStreamException.class);

        if (httpData.isPooled()) {
            assertThat(byteBuf.refCnt()).isEqualTo(0);
        } else {
            assertThat(byteBuf.refCnt()).isEqualTo(1);
            byteBuf.release();
        }

        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testOnRemovalFromOnNext() throws InterruptedException {
        final BlockingQueue<Object> onRemovalQueue = new LinkedTransferQueue<>();
        final EventLoopStreamMessage<Integer> streamMessage =
                new EventLoopStreamMessage<Integer>(eventLoop.get()) {
            @Override
            public void onRemoval(Integer obj) {
                onRemovalQueue.add(obj);
            }
        };

        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get());
        eventLoop.get().execute(() -> streamMessage.write(1));
        eventLoop.get().execute(() -> streamMessage.write(2));
        eventLoop.get().execute(streamMessage::close);

        await().until(() -> subscribed.get() == 1);
        await().until(() -> next.get() == 2);
        await().until(() -> completed.get() == 1);

        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isEqualTo(1);
        assertThat(queue.take()).isEqualTo(2);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();

        assertThat(onRemovalQueue.take()).isEqualTo(1);
        assertThat(onRemovalQueue.take()).isEqualTo(2);
    }

    @Test
    void testOnRemovalFromDrain() throws InterruptedException {
        final BlockingQueue<Object> onRemovalQueue = new LinkedTransferQueue<>();
        final EventLoopStreamMessage<Integer> streamMessage =
                new EventLoopStreamMessage<Integer>(eventLoop.get()) {
                    @Override
                    public void onRemoval(Integer obj) {
                        onRemovalQueue.add(obj);
                    }
                };

        // subscribe without requesting to trigger drain with messages
        streamMessage.subscribe(new DefaultSubscriber<Integer>(subscribeEventLoop.get()) {
        }, subscribeEventLoop.get());

        eventLoop.get().execute(() -> streamMessage.write(1));
        eventLoop.get().execute(() -> streamMessage.write(2));
        eventLoop.get().execute(streamMessage::abort);

        await().until(() -> subscribed.get() == 1);
        await().until(() -> error.get() == 1);

        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isInstanceOf(AbortedStreamException.class);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();

        assertThat(onRemovalQueue.take()).isEqualTo(1);
        assertThat(onRemovalQueue.take()).isEqualTo(2);
    }

    @Test
    void testAbortWithCancelExceptionProceedsNormally() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());

        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get(),
                                SubscriptionOption.NOTIFY_CANCELLATION);
        // run from eventLoop to guarantee run after subscribe
        eventLoop.get().execute(() -> streamMessage.abort(CancelledSubscriptionException.INSTANCE));

        await().until(() -> subscribed.get() == 1);
        await().until(() -> error.get() == 1);

        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isInstanceOf(CancelledSubscriptionException.class);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testCloseWithCancelExceptionThrows() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());

        streamMessage.subscribe(DEFAULT_SUBSCRIBER, subscribeEventLoop.get(),
                                SubscriptionOption.NOTIFY_CANCELLATION);
        // run from eventLoop to guarantee run after subscribe
        final CompletableFuture<Void> closeFuture = CompletableFuture.runAsync(
                () -> streamMessage.close(CancelledSubscriptionException.INSTANCE), eventLoop.get());
        await().until(closeFuture::isDone);

        assertThat(closeFuture).hasFailedWithThrowableThat()
                               .isInstanceOf(IllegalArgumentException.class)
                               .hasMessageContaining("must use Subscription.cancel()");

        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testNegativeRequest() throws InterruptedException {
        final EventLoopStreamMessage<HttpData> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
        eventLoop.get().execute(() -> streamMessage.write(HttpData.wrap(buf).withEndOfStream()));

        streamMessage.subscribe(new DefaultSubscriber<HttpData>(subscribeEventLoop.get()) {
            @Override
            public void onSubscribe(Subscription s) {
                super.onSubscribe(s);
                s.request(-1);
            }
        }, subscribeEventLoop.get());

        await().until(() -> subscribed.get() == 1);
        await().until(() -> error.get() == 1);

        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take())
                .isInstanceOf(IllegalArgumentException.class)
                .extracting(o -> (IllegalArgumentException) o)
                .satisfies(e -> assertThat(e).hasMessageContaining("expected: > 0"));
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();

        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void testCloseAndAbortAfterWrite() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        eventLoop.get().execute(() -> streamMessage.write(1));
        eventLoop.get().execute(streamMessage::close);

        streamMessage.subscribe(new DefaultSubscriber<Integer>(subscribeEventLoop.get()) {
        }, subscribeEventLoop.get());
        await().until(() -> subscribed.get() == 1);

        streamMessage.abort();
        await().until(() -> error.get() == 1);

        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue.take()).isInstanceOf(AbortedStreamException.class);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testCloseAndAbortWithoutWrite() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        eventLoop.get().execute(streamMessage::close);

        streamMessage.subscribe(new DefaultSubscriber<Integer>(subscribeEventLoop.get()) {
        }, subscribeEventLoop.get());
        await().until(() -> subscribed.get() == 1);

        streamMessage.abort();
        await().until(() -> completed.get() == 1);

        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }

    @Test
    void testWhenConsumed() throws InterruptedException {
        final EventLoopStreamMessage<Integer> streamMessage = new EventLoopStreamMessage<>(eventLoop.get());
        final CompletableFuture<Void> whenConsumed = streamMessage.whenConsumed();
        assertThat(whenConsumed).isNotDone();
        eventLoop.get().execute(streamMessage::close);

        streamMessage.subscribe(new DefaultSubscriber<Integer>(subscribeEventLoop.get()) {
        }, subscribeEventLoop.get());
        await().until(() -> subscribed.get() == 1);
        await().until(() -> completed.get() == 1);

        assertThat(whenConsumed).isDone();
        assertThat(queue.take()).isEqualTo(streamMessage);
        assertThat(queue).isEmpty();
        assertThat(streamMessage.queue).isEmpty();
    }
}
