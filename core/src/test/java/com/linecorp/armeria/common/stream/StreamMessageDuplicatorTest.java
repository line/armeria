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

import static com.linecorp.armeria.common.util.Exceptions.clearTrace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.stream.AbstractStreamMessageDuplicator.DownstreamSubscription;
import com.linecorp.armeria.common.stream.AbstractStreamMessageDuplicator.SignalQueue;
import com.linecorp.armeria.common.stream.AbstractStreamMessageDuplicator.StreamMessageProcessor;
import com.linecorp.armeria.testing.internal.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

public class StreamMessageDuplicatorTest {

    private static final Logger logger = LoggerFactory.getLogger(StreamMessageDuplicatorTest.class);

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Test
    public void subscribeTwice() {
        @SuppressWarnings("unchecked")
        final StreamMessage<String> publisher = mock(StreamMessage.class);
        when(publisher.completionFuture()).thenReturn(new CompletableFuture<>());

        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(publisher);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<StreamMessageProcessor<String>> processorCaptor =
                ArgumentCaptor.forClass(StreamMessageProcessor.class);

        verify(publisher).subscribe(processorCaptor.capture(), eq(true));

        verify(publisher).subscribe(any(), eq(true));
        final Subscriber<String> subscriber1 = subscribeWithMock(duplicator.duplicateStream());
        final Subscriber<String> subscriber2 = subscribeWithMock(duplicator.duplicateStream());
        // Publisher's subscribe() is not invoked when a new subscriber subscribes.
        verify(publisher).subscribe(any(), eq(true));

        final StreamMessageProcessor<String> processor = processorCaptor.getValue();

        // Verify that the propagated triggers onSubscribe().
        verify(subscriber1, never()).onSubscribe(any());
        verify(subscriber2, never()).onSubscribe(any());
        processor.onSubscribe(mock(Subscription.class));
        verify(subscriber1).onSubscribe(any(DownstreamSubscription.class));
        verify(subscriber2).onSubscribe(any(DownstreamSubscription.class));
        duplicator.close();
    }

    private static Subscriber<String> subscribeWithMock(StreamMessage<String> streamMessage) {
        @SuppressWarnings("unchecked")
        final Subscriber<String> subscriber = mock(Subscriber.class);
        streamMessage.subscribe(subscriber);
        return subscriber;
    }

    @Test
    public void closePublisherNormally() throws Exception {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(publisher);

        final CompletableFuture<String> future1 = subscribe(duplicator.duplicateStream());
        final CompletableFuture<String> future2 = subscribe(duplicator.duplicateStream());

        writeData(publisher);
        publisher.close();

        assertThat(future1.get()).isEqualTo("Armeria is awesome.");
        assertThat(future2.get()).isEqualTo("Armeria is awesome.");
        duplicator.close();
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
        streamMessage.completionFuture().whenComplete(subscriber);
        streamMessage.subscribe(subscriber);
        return future;
    }

    @Test
    public void closePublisherExceptionally() throws Exception {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(publisher);

        final CompletableFuture<String> future1 = subscribe(duplicator.duplicateStream());
        final CompletableFuture<String> future2 = subscribe(duplicator.duplicateStream());

        writeData(publisher);
        publisher.close(clearTrace(new AnticipatedException()));

        assertThat(future1).isCompletedExceptionally();
        assertThat(future2).isCompletedExceptionally();
        assertThatThrownBy(future1::get).hasCauseExactlyInstanceOf(AnticipatedException.class);
        assertThatThrownBy(future2::get).hasCauseExactlyInstanceOf(AnticipatedException.class);
        duplicator.close();
    }

    @Test
    public void subscribeAfterPublisherClosed() throws Exception {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(publisher);

        final CompletableFuture<String> future1 = subscribe(duplicator.duplicateStream());
        writeData(publisher);
        publisher.close();

        assertThat(future1.get()).isEqualTo("Armeria is awesome.");

        // Still subscribable.
        final CompletableFuture<String> future2 = subscribe(duplicator.duplicateStream());
        assertThat(future2.get()).isEqualTo("Armeria is awesome.");
        duplicator.close();
    }

    @Test
    public void childStreamIsNotClosedWhenDemandIsNotEnough() throws Exception {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(publisher);

        final CompletableFuture<String> future1 = new CompletableFuture<>();
        final StringSubscriber subscriber = new StringSubscriber(future1, 2);
        final StreamMessage<String> sm = duplicator.duplicateStream();
        sm.completionFuture().whenComplete(subscriber);
        sm.subscribe(subscriber);

        final CompletableFuture<String> future2 = subscribe(duplicator.duplicateStream(), 3);

        writeData(publisher);
        publisher.close();

        assertThat(future2.get()).isEqualTo("Armeria is awesome.");
        assertThat(future1.isDone()).isEqualTo(false);

        subscriber.requestAnother();
        assertThat(future1.get()).isEqualTo("Armeria is awesome.");
        duplicator.close();
    }

    @Test
    public void abortPublisherWithSubscribers() {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(publisher);

        final CompletableFuture<String> future = subscribe(duplicator.duplicateStream());
        publisher.abort();

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get).hasCauseExactlyInstanceOf(AbortedStreamException.class);
        duplicator.close();
    }

    @Test
    public void abortPublisherWithoutSubscriber() {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(publisher);
        publisher.abort();

        // Completed exceptionally as soon as a subscriber subscribes.
        final CompletableFuture<String> future = subscribe(duplicator.duplicateStream());
        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get).hasCauseExactlyInstanceOf(AbortedStreamException.class);
        duplicator.close();
    }

    @Test
    public void abortChildStream() {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(publisher);

        final StreamMessage<String> sm1 = duplicator.duplicateStream();
        final CompletableFuture<String> future1 = subscribe(sm1);

        final StreamMessage<String> sm2 = duplicator.duplicateStream();
        final CompletableFuture<String> future2 = subscribe(sm2);

        sm1.abort();
        assertThat(future1).isCompletedExceptionally();
        assertThatThrownBy(future1::get).hasCauseExactlyInstanceOf(AbortedStreamException.class);

        // Aborting from another subscriber does not affect other subscribers.
        assertThat(sm2.isOpen()).isTrue();
        sm2.abort();
        assertThat(future2).isCompletedExceptionally();
        assertThatThrownBy(future2::get).hasCauseExactlyInstanceOf(AbortedStreamException.class);
        duplicator.close();
    }

    @Test
    public void closeMulticastStreamFactory() {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final StreamMessageDuplicator duplicator = new StreamMessageDuplicator(publisher);

        duplicator.close();
        assertThatThrownBy(duplicator::duplicateStream).isInstanceOf(IllegalStateException.class);
    }

    /**
     * A test for the {@link SignalQueue} in {@link AbstractStreamMessageDuplicator}.
     * Queue expansion behaves differently when odd/even number head wrap-around happens.
     */
    @Test
    public void circularQueueOddNumHeadWrapAround() {
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

    private void add(SignalQueue queue, int from, int to) {
        for (int i = from; i < to; i++) {
            queue.addAndRemoveIfRequested(i);
        }
    }

    /**
     * A test for the {@link SignalQueue} in {@link AbstractStreamMessageDuplicator}.
     * Queue expansion behaves differently when odd/even number head wrap-around happens.
     */
    @Test
    public void circularQueueEvenNumHeadWrapAround() {
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
    public void lastDuplicateStream() {
        final DefaultStreamMessage<ByteBuf> publisher = new DefaultStreamMessage<>();
        final ByteBufDuplicator duplicator = new ByteBufDuplicator(publisher);

        duplicator.duplicateStream().subscribe(new ByteBufSubscriber());
        duplicator.duplicateStream(true).subscribe(new ByteBufSubscriber());

        // duplicateStream() is not allowed anymore.
        assertThatThrownBy(duplicator::duplicateStream).isInstanceOf(IllegalStateException.class);

        final ByteBuf[] bufs = new ByteBuf[30];
        for (int i = 0; i < 30; i++) {
            final ByteBuf buf = newUnpooledBuffer();
            bufs[i] = buf;
            assertThat(publisher.write(buf)).isTrue();  // Removing internal caches happens when i = 25
            assertThat(buf.refCnt()).isOne();
        }

        for (int i = 0; i < 25; i++) {  // first 25 signals are removed from the queue.
            assertThat(bufs[i].refCnt()).isZero();
        }
        for (int i = 25; i < 30; i++) {  // rest of them are still in the queue.
            assertThat(bufs[i].refCnt()).isOne();
            bufs[i].release();
        }
    }

    private static ByteBuf newUnpooledBuffer() {
        return UnpooledByteBufAllocator.DEFAULT.buffer().writeByte(0);
    }

    private static class StreamMessageDuplicator
            extends AbstractStreamMessageDuplicator<String, StreamMessage<String>> {
        StreamMessageDuplicator(StreamMessage<String> publisher) {
            super(publisher, String::length, 0);
        }

        @Override
        public StreamMessage<String> doDuplicateStream(StreamMessage<String> delegate) {
            return new StreamMessageWrapper<>(delegate);
        }
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
        public void onError(Throwable t) {
            logger.debug("{}: onError({})", this, String.valueOf(t), t);
        }

        @Override
        public void onComplete() {
            logger.debug("{}: onComplete()", this);
        }

        @Override
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

    private static class ByteBufDuplicator
            extends AbstractStreamMessageDuplicator<ByteBuf, StreamMessage<ByteBuf>> {
        ByteBufDuplicator(StreamMessage<ByteBuf> publisher) {
            super(publisher, ByteBuf::capacity, 0);
        }

        @Override
        protected StreamMessage<ByteBuf> doDuplicateStream(StreamMessage<ByteBuf> delegate) {
            return new StreamMessageWrapper<>(delegate);
        }
    }

    private static class ByteBufSubscriber implements Subscriber<ByteBuf> {
        @Override
        public void onSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuf o) {}

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }
}
