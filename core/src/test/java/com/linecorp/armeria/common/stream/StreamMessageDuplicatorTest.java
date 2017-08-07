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
import com.linecorp.armeria.common.stream.AbstractStreamMessageDuplicator.StreamMessageProcessor;
import com.linecorp.armeria.testing.internal.AnticipatedException;

public class StreamMessageDuplicatorTest {

    private static final Logger logger = LoggerFactory.getLogger(StreamMessageDuplicatorTest.class);

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Test
    public void subscribeTwice() {
        @SuppressWarnings("unchecked")
        final StreamMessage<String> publisher = mock(StreamMessage.class);
        when(publisher.closeFuture()).thenReturn(new CompletableFuture<>());

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
        streamMessage.closeFuture().whenComplete(subscriber);
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
        sm.closeFuture().whenComplete(subscriber);
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

    private static class StreamMessageDuplicator
            extends AbstractStreamMessageDuplicator<String, StreamMessage<String>> {
        StreamMessageDuplicator(StreamMessage<String> publisher) {
            super(publisher);
        }

        @Override
        public StreamMessage<String> doDuplicateStream(StreamMessage<String> delegate) {
            return new MulticastStream(delegate);
        }

        private static class MulticastStream extends StreamMessageWrapper<String> {
            MulticastStream(StreamMessage<String> delegate) {
                super(delegate);
            }
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
            logger.debug("{}: closeFuture({})", this, String.valueOf(cause), cause);
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
}
