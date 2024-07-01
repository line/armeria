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
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Charsets;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.DefaultStreamMessageDuplicator.DownstreamSubscription;
import com.linecorp.armeria.common.stream.DefaultStreamMessageDuplicator.SignalQueue;
import com.linecorp.armeria.common.stream.DefaultStreamMessageDuplicator.StreamMessageProcessor;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.concurrent.ImmediateEventExecutor;

class StreamMessageDuplicatorTest {

    private static final List<ByteBuf> byteBufs = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (ByteBuf byteBuf : byteBufs) {
            assertThat(byteBuf.refCnt()).isZero();
        }
        byteBufs.clear();
    }

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
    void closePublisherNormally() {
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final CompletableFuture<String> future1 = new CompletableFuture<>();
        duplicator.duplicate().subscribe(new HttpDataSubscriber(future1));
        final CompletableFuture<String> future2 = new CompletableFuture<>();
        duplicator.duplicate().subscribe(new HttpDataSubscriber(future2));

        writeData(publisher);
        publisher.close();

        assertThat(future1.join()).isEqualTo("Armeria is awesome.");
        assertThat(future2.join()).isEqualTo("Armeria is awesome.");
        duplicator.abort();
    }

    private static void writeData(StreamWriter<HttpData> publisher) {
        publisher.write(httpData("Armeria "));
        publisher.write(httpData("is "));
        publisher.write(httpData("awesome."));
    }

    @Test
    void closePublisherExceptionally() {
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final CompletableFuture<String> future1 = new CompletableFuture<>();
        duplicator.duplicate().subscribe(new HttpDataSubscriber(future1));
        final CompletableFuture<String> future2 = new CompletableFuture<>();
        duplicator.duplicate().subscribe(new HttpDataSubscriber(future2));

        writeData(publisher);
        publisher.close(clearTrace(new AnticipatedException()));

        assertThatThrownBy(future1::join).hasCauseInstanceOf(AnticipatedException.class);
        assertThatThrownBy(future2::join).hasCauseInstanceOf(AnticipatedException.class);
        duplicator.abort();
    }

    @Test
    void subscribeAfterPublisherClosed() {
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final CompletableFuture<String> future1 = new CompletableFuture<>();
        duplicator.duplicate().subscribe(new HttpDataSubscriber(future1));
        writeData(publisher);
        publisher.close();

        assertThat(future1.join()).isEqualTo("Armeria is awesome.");

        // Still subscribable.
        final CompletableFuture<String> future2 = new CompletableFuture<>();
        duplicator.duplicate().subscribe(new HttpDataSubscriber(future2));
        assertThat(future2.join()).isEqualTo("Armeria is awesome.");
        duplicator.abort();
    }

    @Test
    void childStreamIsNotClosedWhenDemandIsNotEnough() {
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final CompletableFuture<String> future1 = new CompletableFuture<>();
        final HttpDataSubscriber subscriber = new HttpDataSubscriber(future1, 2);
        duplicator.duplicate().subscribe(subscriber);
        final CompletableFuture<String> future2 = new CompletableFuture<>();
        duplicator.duplicate().subscribe(new HttpDataSubscriber(future2, 3));

        writeData(publisher);
        publisher.close();

        assertThat(future2.join()).isEqualTo("Armeria is awesome.");
        assertThat(future1.isDone()).isEqualTo(false);

        subscriber.requestAnother();
        assertThat(future1.join()).isEqualTo("Armeria is awesome.");
        duplicator.abort();
    }

    @Test
    void abortPublisherWithSubscribers() {
        for (Throwable abortCause : ABORT_CAUSES) {
            final StreamWriter<HttpData> publisher = StreamMessage.streaming();
            final StreamMessageDuplicator<HttpData> duplicator =
                    publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

            final CompletableFuture<String> future = new CompletableFuture<>();
            duplicator.duplicate().subscribe(new HttpDataSubscriber(future));
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
            final StreamWriter<HttpData> publisher = StreamMessage.streaming();
            final StreamMessageDuplicator<HttpData> duplicator =
                    publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);
            if (abortCause == null) {
                publisher.abort();
            } else {
                publisher.abort(abortCause);
            }

            // Completed exceptionally once a subscriber subscribes.
            final CompletableFuture<String> future = new CompletableFuture<>();
            duplicator.duplicate().subscribe(new HttpDataSubscriber(future));
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
            final StreamWriter<HttpData> publisher = StreamMessage.streaming();
            final StreamMessageDuplicator<HttpData> duplicator =
                    publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

            final StreamMessage<HttpData> sm1 = duplicator.duplicate();
            final CompletableFuture<String> future1 = new CompletableFuture<>();
            sm1.subscribe(new HttpDataSubscriber(future1));

            final StreamMessage<HttpData> sm2 = duplicator.duplicate();
            final CompletableFuture<String> future2 = new CompletableFuture<>();
            sm2.subscribe(new HttpDataSubscriber(future2));

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
    void abortedChildStreamShouldNotLeakPublisherElements() {
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        publisher.write(httpData(0));

        try (StreamMessageDuplicator<HttpData> duplicator =
                     publisher.toDuplicator(ImmediateEventExecutor.INSTANCE)) {

            final StreamMessage<HttpData> child = duplicator.duplicate();
            child.abort();

            // Ensure the child did not consume the element.
            assertThat(byteBufs.get(0).refCnt()).isOne();
        }

        // The duplicator should clean up the published elements after duplicator.close()
        // since a child can't be created anymore.
        assertThat(byteBufs.get(0).refCnt()).isZero();
    }

    @Test
    void duplicateToClosedDuplicator() {
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
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
        assertRefCnt(0, 10, 1);
        assertThat(queue.size()).isEqualTo(10);
        queue.requestRemovalAheadOf(8);
        assertThat(queue.size()).isEqualTo(10); // removing elements happens when adding a element

        int removedLength = queue.addAndRemoveIfRequested(httpData(10));
        assertThat(removedLength).isEqualTo(8 * 4);
        assertThat(queue.size()).isEqualTo(3); // 11 - 8 elements
        assertRefCnt(0, 8, 0);
        assertRefCnt(8, 11, 1);

        add(queue, 11, 20);
        queue.requestRemovalAheadOf(20); // head wrap around happens
        assertRefCnt(8, 20, 1);
        assertThat(queue.elements.length).isEqualTo(16);

        removedLength = queue.addAndRemoveIfRequested(httpData(20));
        assertRefCnt(0, 20, 0);
        assertRefCnt(20, 21, 1);
        assertThat(removedLength).isEqualTo(12 * 4);

        add(queue, 21, 40); // queue expansion happens
        assertThat(queue.elements.length).isEqualTo(32);
        assertRefCnt(0, 20, 0);
        assertRefCnt(20, 40, 1);

        for (int i = 20; i < 40; i++) {
            assertThat(((HttpData) queue.get(i)).byteBuf().readInt()).isEqualTo(i);
        }
        assertThat(queue.size()).isEqualTo(20);
        queue.clear(null);
        assertRefCnt(0, 40, 0);
    }

    /**
     * A test for the {@link SignalQueue} in {@link DefaultStreamMessageDuplicator}.
     * Queue expansion behaves differently when odd/even number head wrap-around happens.
     */
    @Test
    void circularQueueEvenNumHeadWrapAround() {
        final SignalQueue queue = new SignalQueue(obj -> 4);
        add(queue, 0, 10);
        assertRefCnt(0, 10, 1);

        queue.requestRemovalAheadOf(10);
        add(queue, 10, 20);
        assertRefCnt(0, 10, 0);
        assertRefCnt(10, 20, 1);

        queue.requestRemovalAheadOf(20); // first head wrap around
        add(queue, 20, 30);
        assertRefCnt(0, 20, 0);
        assertRefCnt(20, 30, 1);

        queue.requestRemovalAheadOf(30);
        add(queue, 30, 40);
        assertRefCnt(0, 30, 0);
        assertRefCnt(30, 40, 1);

        queue.requestRemovalAheadOf(40); // second head wrap around
        add(queue, 40, 60);              // queue expansion happens
        assertRefCnt(0, 40, 0);
        assertRefCnt(40, 60, 1);
        for (int i = 40; i < 60; i++) {
            assertThat(((HttpData) queue.get(i)).byteBuf().readInt()).isEqualTo(i);
        }
        queue.clear(null);
        assertRefCnt(0, 60, 0);
    }

    @Test
    void publishedSignalsCleanedUpWhenDuplicatorIsClosed() {
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final StreamMessage<HttpData> duplicated1 = duplicator.duplicate();
        final StreamMessage<HttpData> duplicated2 = duplicator.duplicate();
        duplicator.close();
        // duplicate() is not allowed anymore.
        assertThatThrownBy(duplicator::duplicate).isInstanceOf(IllegalStateException.class);

        duplicated1.subscribe(new HttpDataSubscriber(), ImmediateEventExecutor.INSTANCE);
        duplicated2.subscribe(new HttpDataSubscriber(), ImmediateEventExecutor.INSTANCE);

        for (int i = 0; i < 30; i++) {
            final HttpData httpData = httpData(i);
            publisher.write(httpData);
            assertThat(httpData.byteBuf().refCnt()).isOne();
        }

        assertRefCnt(0, 25, 0); // first 25 signals are removed from the queue.
        assertRefCnt(25, 30, 1); // rest of them are still in the queue.
        duplicator.abort();

        assertRefCnt(0, 30, 0);  // rest of them are cleared after calling duplicator.abort()
    }

    @Test
    void closingDuplicatorDoesNotAbortDuplicatedStream() {
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);
        final HttpDataSubscriber subscriber = new HttpDataSubscriber();

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
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);

        final HttpData httpData = httpData(0);
        publisher.write(httpData);
        assertThat(httpData.byteBuf().refCnt()).isOne();

        // Release the buf after writing to the publisher which must not happen!
        httpData.byteBuf().release();

        final HttpDataSubscriber subscriber = new HttpDataSubscriber();
        duplicator.duplicate().subscribe(subscriber, ImmediateEventExecutor.INSTANCE);
        assertThatThrownBy(() -> subscriber.completionFuture().join()).hasCauseInstanceOf(
                IllegalReferenceCountException.class);
    }

    @Test
    void withPooledObjects() {
        final HttpData httpData = httpData(0);
        final ByteBuf byteBuf = httpData.byteBuf();
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);
        publisher.write(httpData);
        publisher.close();

        final AtomicBoolean completed = new AtomicBoolean();
        duplicator.duplicate().subscribe(new Subscriber<HttpData>() {
            @Nullable
            Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription) {
                // Cancel the subscription when the demand is 0.
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(HttpData d) {
                assertThat(byteBuf.refCnt()).isEqualTo(2);
                subscription.cancel();
                d.close();
                completed.set(true);
            }

            @Override
            public void onError(Throwable throwable) {
                // This is not called because we didn't specify NOTIFY_CANCELLATION when subscribe.
                fail("unexpected onError()", throwable);
            }

            @Override
            public void onComplete() {
                fail("unexpected onComplete()");
            }
        }, WITH_POOLED_OBJECTS);

        await().untilAsserted(() -> assertThat(completed).isTrue());
        assertThat(byteBuf.refCnt()).isOne();
        duplicator.close();
        assertThat(byteBuf.refCnt()).isZero();
    }

    @Test
    void unpooledByDefault() {
        final HttpData httpData = httpData(0);
        final ByteBuf byteBuf = httpData.byteBuf();
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);
        publisher.write(httpData);
        publisher.close();

        final AtomicBoolean completed = new AtomicBoolean();
        duplicator.duplicate().subscribe(new Subscriber<HttpData>() {
            @Nullable
            Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription) {
                // Cancel the subscription when the demand is 0.
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(HttpData d) {
                assertThat(byteBuf.refCnt()).isOne();
                subscription.cancel();
                d.close();
                completed.set(true);
            }

            @Override
            public void onError(Throwable throwable) {
                // This is not called because we didn't specify NOTIFY_CANCELLATION when subscribe.
                fail("unexpected onError()", throwable);
            }

            @Override
            public void onComplete() {
                fail("unexpected onComplete()");
            }
        });

        await().untilAsserted(() -> assertThat(completed).isTrue());
        assertThat(byteBuf.refCnt()).isOne();
        duplicator.close();
        assertThat(byteBuf.refCnt()).isZero();
    }

    @Test
    void notifyCancellation() {
        final ByteBuf data = newPooledBuffer();
        final StreamWriter<HttpData> publisher = StreamMessage.streaming();
        final StreamMessageDuplicator<HttpData> duplicator =
                publisher.toDuplicator(ImmediateEventExecutor.INSTANCE);
        publisher.write(HttpData.wrap(data));
        publisher.close();

        final AtomicBoolean completed = new AtomicBoolean();
        duplicator.duplicate().subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.cancel();
            }

            @Override
            public void onNext(HttpData d) {
                fail("unexpected onNext(): %s", d);
            }

            @Override
            public void onError(Throwable t) {
                assertThat(t).isInstanceOf(CancelledSubscriptionException.class);
                completed.set(true);
            }

            @Override
            public void onComplete() {
                fail("unexpected onComplete()");
            }
        }, NOTIFY_CANCELLATION);

        await().untilAsserted(() -> assertThat(completed).isTrue());
        duplicator.close();
    }

    private static void add(SignalQueue queue, int from, int to) {
        for (int i = from; i < to; i++) {
            queue.addAndRemoveIfRequested(httpData(i));
        }
    }

    private static HttpData httpData(int i) {
        final ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.buffer(4).writeInt(i);
        byteBufs.add(buf);
        return HttpData.wrap(buf);
    }

    private static HttpData httpData(String str) {
        final ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.buffer(str.length());
        buf.writeCharSequence(str, Charsets.UTF_8);
        byteBufs.add(buf);
        return HttpData.wrap(buf);
    }

    private static void assertRefCnt(int start, int end, int refCnt) {
        for (int i = start; i < end; i++) {
            assertThat(byteBufs.get(i).refCnt()).isEqualTo(refCnt);
        }
    }

    private static class HttpDataSubscriber implements Subscriber<HttpData> {

        private final CompletableFuture<String> future;
        private final StringBuffer sb = new StringBuffer();
        private final long demand;
        private Subscription subscription;

        HttpDataSubscriber() {
            this(new CompletableFuture<>(), Long.MAX_VALUE);
        }

        HttpDataSubscriber(CompletableFuture<String> future) {
            this(future, Long.MAX_VALUE);
        }

        HttpDataSubscriber(CompletableFuture<String> future, long demand) {
            this.future = future;
            this.demand = demand;
        }

        void requestAnother() {
            subscription.request(1);
        }

        CompletableFuture<String> completionFuture() {
            return future;
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            s.request(demand);
        }

        @Override
        public void onNext(HttpData o) {
            sb.append(o.toStringUtf8());
        }

        @Override
        public void onError(Throwable throwable) {
            future.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            future.complete(sb.toString());
        }
    }
}
