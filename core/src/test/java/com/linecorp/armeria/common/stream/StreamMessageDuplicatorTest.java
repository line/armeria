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

import static com.linecorp.armeria.common.stream.AbortCauseArgumentProvider.ABORT_CAUSES;
import static com.linecorp.armeria.common.stream.StreamMessageTest.newPooledBuffer;
import static com.linecorp.armeria.common.stream.SubscriptionOption.NOTIFY_CANCELLATION;
import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static com.linecorp.armeria.common.util.Exceptions.clearTrace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.stream.DefaultStreamMessageDuplicator.DownstreamSubscription;
import com.linecorp.armeria.common.stream.DefaultStreamMessageDuplicator.SignalQueue;
import com.linecorp.armeria.common.stream.DefaultStreamMessageDuplicator.StreamMessageProcessor;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.concurrent.ImmediateEventExecutor;

class StreamMessageDuplicatorTest {

    private static final Logger logger = LoggerFactory.getLogger(StreamMessageDuplicatorTest.class);

    @Test
    void subscribeTwice() {
        @SuppressWarnings("unchecked")
        final StreamMessage<String> publisher = mock(StreamMessage.class);
        when(publisher.toDuplicator(any())).thenCallRealMethod();

        final StreamMessageDuplicator<String> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<StreamMessageProcessor<String>> processorCaptor =
                ArgumentCaptor.forClass(StreamMessageProcessor.class);

        verify(publisher).subscribe(processorCaptor.capture(), eq(ImmediateEventExecutor.INSTANCE),
                                    eq(WITH_POOLED_OBJECTS), eq(NOTIFY_CANCELLATION));

        final Subscriber<String> subscriber1 = subscribeWithMock(duplicator.duplicate());
        final Subscriber<String> subscriber2 = subscribeWithMock(duplicator.duplicate());
        // Publisher's subscribe() is not invoked when a new subscriber subscribes.
        verify(publisher).subscribe(any(), eq(ImmediateEventExecutor.INSTANCE),
                                    eq(WITH_POOLED_OBJECTS), eq(NOTIFY_CANCELLATION));

        final StreamMessageProcessor<String> processor = processorCaptor.getValue();

        // Verify that the propagated triggers onSubscribe().
        verify(subscriber1, never()).onSubscribe(any());
        verify(subscriber2, never()).onSubscribe(any());
        processor.onSubscribe(mock(Subscription.class));
        verify(subscriber1).onSubscribe(any(DownstreamSubscription.class));
        verify(subscriber2).onSubscribe(any(DownstreamSubscription.class));
        duplicator.abort();
    }

    private static Subscriber<String> subscribeWithMock(StreamMessage<String> streamMessage) {
        @SuppressWarnings("unchecked")
        final Subscriber<String> subscriber = mock(Subscriber.class);
        streamMessage.subscribe(subscriber, ImmediateEventExecutor.INSTANCE);
        return subscriber;
    }

    @Test
    void closePublisherNormally() throws Exception {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<String> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final CompletableFuture<String> future1 = subscribe(duplicator.duplicate());
        final CompletableFuture<String> future2 = subscribe(duplicator.duplicate());

        writeData(publisher);
        publisher.close();

        assertThat(future1.get()).isEqualTo("Armeria is awesome.");
        assertThat(future2.get()).isEqualTo("Armeria is awesome.");
        duplicator.abort();
    }

    private static void writeData(DefaultStreamMessage<String> publisher) {
        publisher.write("Armeria ");
        publisher.write("is ");
        publisher.write("awesome.");
    }

    private static CompletableFuture<String> subscribe(StreamMessage<String> streamMessage) {
        return subscribe(streamMessage, Long.MAX_VALUE);
    }

    private static CompletableFuture<String> subscribe(StreamMessage<String> streamMessage, long demand) {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final StringSubscriber subscriber = new StringSubscriber(future, demand);
        streamMessage.whenComplete().whenComplete(subscriber);
        streamMessage.subscribe(subscriber);
        return future;
    }

    @Test
    void closePublisherExceptionally() throws Exception {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<String> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final CompletableFuture<String> future1 = subscribe(duplicator.duplicate());
        final CompletableFuture<String> future2 = subscribe(duplicator.duplicate());

        writeData(publisher);
        publisher.close(clearTrace(new AnticipatedException()));

        assertThatThrownBy(future1::join).hasCauseInstanceOf(AnticipatedException.class);
        assertThatThrownBy(future2::join).hasCauseInstanceOf(AnticipatedException.class);
        duplicator.abort();
    }

    @Test
    void subscribeAfterPublisherClosed() throws Exception {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<String> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final CompletableFuture<String> future1 = subscribe(duplicator.duplicate());
        writeData(publisher);
        publisher.close();

        assertThat(future1.get()).isEqualTo("Armeria is awesome.");

        // Still subscribable.
        final CompletableFuture<String> future2 = subscribe(duplicator.duplicate());
        assertThat(future2.get()).isEqualTo("Armeria is awesome.");
        duplicator.abort();
    }

    @Test
    void childStreamIsNotClosedWhenDemandIsNotEnough() throws Exception {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<String> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final CompletableFuture<String> future1 = new CompletableFuture<>();
        final StringSubscriber subscriber = new StringSubscriber(future1, 2);
        final StreamMessage<String> sm = duplicator.duplicate();
        sm.whenComplete().whenComplete(subscriber);
        sm.subscribe(subscriber);

        final CompletableFuture<String> future2 = subscribe(duplicator.duplicate(), 3);

        writeData(publisher);
        publisher.close();

        assertThat(future2.get()).isEqualTo("Armeria is awesome.");
        assertThat(future1.isDone()).isEqualTo(false);

        subscriber.requestAnother();
        assertThat(future1.get()).isEqualTo("Armeria is awesome.");
        duplicator.abort();
    }

    @Test
    void abortPublisherWithSubscribers() {
        for (Throwable abortCause : ABORT_CAUSES) {
            final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
            final StreamMessageDuplicator<String> duplicator =
                    publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

            final CompletableFuture<String> future = subscribe(duplicator.duplicate());
            if (abortCause == null) {
                publisher.abort();
            } else {
                publisher.abort(abortCause);
            }

            if (abortCause == null) {
                assertThatThrownBy(future::join).hasCauseInstanceOf(AbortedStreamException.class);
            } else {
                assertThatThrownBy(future::join).hasCauseInstanceOf(abortCause.getClass());
            }
            duplicator.abort();
        }
    }

    @Test
    void abortPublisherWithoutSubscriber() {
        for (Throwable abortCause : ABORT_CAUSES) {
            final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
            final StreamMessageDuplicator<String> duplicator =
                    publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);
            if (abortCause == null) {
                publisher.abort();
            } else {
                publisher.abort(abortCause);
            }

            // Completed exceptionally once a subscriber subscribes.
            final CompletableFuture<String> future = subscribe(duplicator.duplicate());
            if (abortCause == null) {
                assertThatThrownBy(future::join).hasCauseInstanceOf(AbortedStreamException.class);
            } else {
                assertThatThrownBy(future::join).hasCauseInstanceOf(abortCause.getClass());
            }
            duplicator.abort();
        }
    }

    @Test
    void abortChildStream() {
        for (Throwable abortCause : ABORT_CAUSES) {
            final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
            final StreamMessageDuplicator<String> duplicator =
                    publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

            final StreamMessage<String> sm1 = duplicator.duplicate();
            final CompletableFuture<String> future1 = subscribe(sm1);

            final StreamMessage<String> sm2 = duplicator.duplicate();
            final CompletableFuture<String> future2 = subscribe(sm2);

            if (abortCause == null) {
                sm1.abort();
                assertThatThrownBy(future1::join).hasCauseInstanceOf(AbortedStreamException.class);
            } else {
                sm1.abort(abortCause);
                assertThatThrownBy(future1::join).hasCauseInstanceOf(abortCause.getClass());
            }

            // Aborting from another subscriber does not affect other subscribers.
            assertThat(sm2.isOpen()).isTrue();
            if (abortCause == null) {
                sm2.abort();
                assertThatThrownBy(future2::join).hasCauseInstanceOf(AbortedStreamException.class);
            } else {
                sm2.abort(abortCause);
                assertThatThrownBy(future2::join).hasCauseInstanceOf(abortCause.getClass());
            }
            duplicator.abort();
        }
    }

    @Test
    void duplicateToClosedDuplicator() {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<String> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        duplicator.close();
        assertThatThrownBy(duplicator::duplicate).isInstanceOf(IllegalStateException.class);
    }

    /**
     * A test for the {@link SignalQueue} in {@link DefaultStreamMessageDuplicator}.
     * Queue expansion behaves differently when odd/even number head wrap-around happens.
     */
    @Test
    void circularQueueOddNumHeadWrapAround() {
        final SignalQueue queue = new SignalQueue(obj -> 4);
        add(queue, 0, 10);
        assertThat(queue.size()).isEqualTo(10);
        queue.requestRemovalAheadOf(8);
        assertThat(queue.size()).isEqualTo(10); // removing elements happens when adding a element

        int removedLength = queue.addAndRemoveIfRequested(10);
        assertThat(removedLength).isEqualTo(8 * 4);
        assertThat(queue.size()).isEqualTo(3); // 11 - 8 elements

        add(queue, 11, 20);
        queue.requestRemovalAheadOf(20); // head wrap around happens
        assertThat(queue.elements.length).isEqualTo(16);

        removedLength = queue.addAndRemoveIfRequested(20);
        assertThat(removedLength).isEqualTo(12 * 4);

        add(queue, 21, 40);              // queue expansion happens
        assertThat(queue.elements.length).isEqualTo(32);
        for (int i = 20; i < 40; i++) {
            assertThat(queue.get(i)).isEqualTo(i);
        }
        assertThat(queue.size()).isEqualTo(20);
    }

    private static void add(SignalQueue queue, int from, int to) {
        for (int i = from; i < to; i++) {
            queue.addAndRemoveIfRequested(i);
        }
    }

    /**
     * A test for the {@link SignalQueue} in {@link DefaultStreamMessageDuplicator}.
     * Queue expansion behaves differently when odd/even number head wrap-around happens.
     */
    @Test
    void circularQueueEvenNumHeadWrapAround() {
        final SignalQueue queue = new SignalQueue(obj -> 4);
        add(queue, 0, 10);
        queue.requestRemovalAheadOf(10);
        add(queue, 10, 20);
        queue.requestRemovalAheadOf(20); // first head wrap around
        add(queue, 20, 30);
        queue.requestRemovalAheadOf(30);
        add(queue, 30, 40);
        queue.requestRemovalAheadOf(40); // second head wrap around
        add(queue, 40, 60);              // queue expansion happens
        for (int i = 40; i < 60; i++) {
            assertThat(queue.get(i)).isEqualTo(i);
        }
    }

    @Test
    void publishedSignalsCleanedUpWhenDuplicatorIsClosed() {
        final DefaultStreamMessage<ByteBuf> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<ByteBuf> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final StreamMessage<ByteBuf> duplicated1 = duplicator.duplicate();
        final StreamMessage<ByteBuf> duplicated2 = duplicator.duplicate();
        duplicator.close();
        // duplicate() is not allowed anymore.
        assertThatThrownBy(duplicator::duplicate).isInstanceOf(IllegalStateException.class);

        duplicated1.subscribe(new ByteBufSubscriber(), ImmediateEventExecutor.INSTANCE);
        duplicated2.subscribe(new ByteBufSubscriber(), ImmediateEventExecutor.INSTANCE);

        // Only used to read refCnt, not an actual reference.
        final ByteBuf[] bufs = new ByteBuf[30];
        for (int i = 0; i < 30; i++) {
            final ByteBuf buf = newUnpooledBuffer();
            bufs[i] = buf;
            publisher.write(buf);
            assertThat(buf.refCnt()).isOne();
        }

        for (int i = 0; i < 25; i++) {  // first 25 signals are removed from the queue.
            assertThat(bufs[i].refCnt()).isZero();
        }
        for (int i = 25; i < 30; i++) {  // rest of them are still in the queue.
            assertThat(bufs[i].refCnt()).isOne();
        }
        duplicator.abort();

        for (int i = 25; i < 30; i++) {  // rest of them are cleared after calling duplicator.abort()
            assertThat(bufs[i].refCnt()).isZero();
        }
    }

    @Test
    void closingDuplicatorDoesNotAbortDuplicatedStream() {
        final DefaultStreamMessage<ByteBuf> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<ByteBuf> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);
        final ByteBufSubscriber subscriber = new ByteBufSubscriber();

        duplicator.duplicate().subscribe(subscriber, ImmediateEventExecutor.INSTANCE);
        duplicator.close();
        // duplicate() is not allowed anymore.
        assertThatThrownBy(duplicator::duplicate).isInstanceOf(IllegalStateException.class);

        assertThat(subscriber.completionFuture().isDone()).isFalse();
        publisher.close();
        assertThat(subscriber.completionFuture().isDone()).isTrue();
    }

    @Test
    void raiseExceptionInOnNext() {
        final DefaultStreamMessage<ByteBuf> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<ByteBuf> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final ByteBuf buf = newUnpooledBuffer();
        publisher.write(buf);
        assertThat(buf.refCnt()).isOne();

        // Release the buf after writing to the publisher which must not happen!
        buf.release();

        final ByteBufSubscriber subscriber = new ByteBufSubscriber();
        duplicator.duplicate().subscribe(subscriber, ImmediateEventExecutor.INSTANCE);
        assertThatThrownBy(() -> subscriber.completionFuture().get()).hasCauseInstanceOf(
                IllegalReferenceCountException.class);
    }

    @Test
    void withPooledObjects() {
        final ByteBuf data = newPooledBuffer();
        final DefaultStreamMessage<ByteBuf> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<ByteBuf> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);
        publisher.write(data);
        publisher.close();

        final AtomicBoolean completed = new AtomicBoolean();
        duplicator.duplicate().subscribe(new Subscriber<ByteBuf>() {
            @Nullable
            Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription) {
                // Cancel the subscription when the demand is 0.
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBuf b) {
                assertThat(data.refCnt()).isEqualTo(2);
                subscription.cancel();
                b.release();
                completed.set(true);
            }

            @Override
            public void onError(Throwable throwable) {
                // This is not called because we didn't specify NOTIFY_CANCELLATION when subscribe.
                fail();
            }

            @Override
            public void onComplete() {
                fail();
            }
        }, WITH_POOLED_OBJECTS);

        await().untilAsserted(() -> assertThat(completed).isTrue());
        assertThat(data.refCnt()).isOne();
        duplicator.close();
        assertThat(data.refCnt()).isZero();
    }

    @Test
    void unpooledByDefault() {
        final ByteBuf data = newPooledBuffer();
        final DefaultStreamMessage<ByteBuf> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<ByteBuf> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);
        publisher.write(data);
        publisher.close();

        final AtomicBoolean completed = new AtomicBoolean();
        duplicator.duplicate().subscribe(new Subscriber<ByteBuf>() {
            @Nullable
            Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription) {
                // Cancel the subscription when the demand is 0.
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBuf b) {
                assertThat(data.refCnt()).isOne();
                subscription.cancel();
                b.release();
                completed.set(true);
            }

            @Override
            public void onError(Throwable throwable) {
                // This is not called because we didn't specify NOTIFY_CANCELLATION when subscribe.
                fail();
            }

            @Override
            public void onComplete() {
                fail();
            }
        });

        await().untilAsserted(() -> assertThat(completed).isTrue());
        assertThat(data.refCnt()).isOne();
        duplicator.close();
        assertThat(data.refCnt()).isZero();
    }

    @Test
    void notifyCancellation() {
        final ByteBuf data = newPooledBuffer();
        final DefaultStreamMessage<ByteBuf> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator<ByteBuf> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);
        publisher.write(data);
        publisher.close();

        final AtomicBoolean completed = new AtomicBoolean();
        duplicator.duplicate().subscribe(new Subscriber<ByteBuf>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.cancel();
            }

            @Override
            public void onNext(ByteBuf byteBuf) {
                fail();
            }

            @Override
            public void onError(Throwable t) {
                assertThat(t).isInstanceOf(CancelledSubscriptionException.class);
                completed.set(true);
            }

            @Override
            public void onComplete() {
                fail();
            }
        }, NOTIFY_CANCELLATION);

        await().untilAsserted(() -> assertThat(completed).isTrue());
        duplicator.close();
    }

    private static ByteBuf newUnpooledBuffer() {
        return UnpooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
    }

    private static class StringSubscriber implements Subscriber<String>, BiConsumer<Void, Throwable> {

        private final CompletableFuture<String> future;
        private final StringBuffer sb = new StringBuffer();
        private final long demand;
        private Subscription subscription;

        StringSubscriber(CompletableFuture<String> future, long demand) {
            this.future = future;
            this.demand = demand;
        }

        @Override
        public void onSubscribe(Subscription s) {
            logger.debug("{}: onSubscribe({})", this, Integer.toHexString(System.identityHashCode(s)));
            subscription = s;
            s.request(demand);
        }

        @Override
        public void onNext(String s) {
            logger.debug("{}: onNext(\"{}\")", this, s);
            sb.append(s);
        }

        @Override
        @SuppressWarnings("UnnecessaryCallToStringValueOf")
        public void onError(Throwable t) {
            logger.debug("{}: onError({})", this, String.valueOf(t), t);
        }

        @Override
        public void onComplete() {
            logger.debug("{}: onComplete()", this);
        }

        @Override
        @SuppressWarnings("UnnecessaryCallToStringValueOf")
        public void accept(Void aVoid, Throwable cause) {
            logger.debug("{}: completionFuture({})", this, String.valueOf(cause), cause);
            if (cause != null) {
                future.completeExceptionally(cause);
            } else {
                future.complete(sb.toString());
            }
        }

        void requestAnother() {
            subscription.request(1);
        }

        @Override
        public String toString() {
            return Integer.toHexString(hashCode());
        }
    }

    private static class ByteBufSubscriber implements Subscriber<ByteBuf> {

        private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        public CompletableFuture<Void> completionFuture() {
            return completionFuture;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuf o) {}

        @Override
        public void onError(Throwable throwable) {
            completionFuture.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completionFuture.complete(null);
        }
    }
}
