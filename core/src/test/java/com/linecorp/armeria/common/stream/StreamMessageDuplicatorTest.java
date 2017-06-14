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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.AbstractStreamMessageDuplicator.DownstreamSubscription;
import com.linecorp.armeria.common.stream.AbstractStreamMessageDuplicator.StreamMessageProcessor;

public class StreamMessageDuplicatorTest {

    @Test
    public void subscribeTwice() {
        @SuppressWarnings("unchecked")
        final StreamMessage<String> publisher = mock(StreamMessage.class);
        when(publisher.closeFuture()).thenReturn(new CompletableFuture<>());

        final AbstractStreamMessageDuplicator<String, StreamMessage<String>> duplicator =
                new StreamMessageDuplicator(publisher);

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

    private Subscriber<String> subscribeWithMock(StreamMessage<String> streamMessage) {
        @SuppressWarnings("unchecked")
        final Subscriber<String> subscriber = mock(Subscriber.class);
        streamMessage.subscribe(subscriber);
        return subscriber;
    }

    @Test
    public void closePublisherNormally() throws Exception {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final AbstractStreamMessageDuplicator<String, StreamMessage<String>> duplicator =
                new StreamMessageDuplicator(publisher);

        final CompletableFuture<String> future1 = subscribe(duplicator.duplicateStream());
        final CompletableFuture<String> future2 = subscribe(duplicator.duplicateStream());

        writeData(publisher);
        publisher.close();

        assertThat(future1.get()).isEqualTo("Armeria is awesome.");
        assertThat(future2.get()).isEqualTo("Armeria is awesome.");
        duplicator.close();
    }

    private void writeData(DefaultStreamMessage<String> publisher) {
        publisher.write("Armeria ");
        publisher.write("is ");
        publisher.write("awesome.");
    }

    private CompletableFuture<String> subscribe(StreamMessage<String> streamMessage) {
        return subscribe(streamMessage, Long.MAX_VALUE);
    }

    private CompletableFuture<String> subscribe(StreamMessage<String> streamMessage, long demand) {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final StringSubscriber subscriber = new StringSubscriber(future, demand);
        streamMessage.closeFuture().whenComplete(subscriber);
        streamMessage.subscribe(subscriber);
        return future;
    }

    @Test
    public void closePublisherExceptionally() throws ExecutionException, InterruptedException {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final AbstractStreamMessageDuplicator<String, StreamMessage<String>> duplicator =
                new StreamMessageDuplicator(publisher);

        final CompletableFuture<String> future1 = subscribe(duplicator.duplicateStream());
        final CompletableFuture<String> future2 = subscribe(duplicator.duplicateStream());

        writeData(publisher);
        publisher.close(new IllegalArgumentException());

        assertThat(future1).isCompletedExceptionally();
        assertThat(future2).isCompletedExceptionally();
        assertThatThrownBy(future1::get).hasCauseExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(future2::get).hasCauseExactlyInstanceOf(IllegalArgumentException.class);
        duplicator.close();
    }

    @Test
    public void subscribeAfterPublisherClosed() throws Exception {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final AbstractStreamMessageDuplicator<String, StreamMessage<String>> duplicator =
                new StreamMessageDuplicator(publisher);

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
    public void childStreamIsNotClosedWhenDemandIsNotEnough()
            throws ExecutionException, InterruptedException {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final AbstractStreamMessageDuplicator<String, StreamMessage<String>> duplicator =
                new StreamMessageDuplicator(publisher);

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
        final AbstractStreamMessageDuplicator<String, StreamMessage<String>> duplicator =
                new StreamMessageDuplicator(publisher);

        final CompletableFuture<String> future = subscribe(duplicator.duplicateStream());
        publisher.abort();

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get).hasCauseExactlyInstanceOf(CancelledSubscriptionException.class);
        duplicator.close();
    }

    @Test
    public void abortPublisherWithoutSubscriber() {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final AbstractStreamMessageDuplicator<String, StreamMessage<String>> duplicator =
                new StreamMessageDuplicator(publisher);
        publisher.abort();

        // Completed exceptionally as soon as a subscriber subscribes.
        final CompletableFuture<String> future = subscribe(duplicator.duplicateStream());
        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get).hasCauseExactlyInstanceOf(CancelledSubscriptionException.class);
        duplicator.close();
    }

    @Test
    public void abortChildStream() {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final AbstractStreamMessageDuplicator<String, StreamMessage<String>> duplicator =
                new StreamMessageDuplicator(publisher);

        final StreamMessage<String> sm1 = duplicator.duplicateStream();
        final CompletableFuture<String> future1 = subscribe(sm1);

        final StreamMessage<String> sm2 = duplicator.duplicateStream();
        final CompletableFuture<String> future2 = subscribe(sm2);

        sm1.abort();
        assertThat(future1).isCompletedExceptionally();
        assertThatThrownBy(future1::get).hasCauseExactlyInstanceOf(CancelledSubscriptionException.class);

        // Aborting from another subscriber does not affect to other subscribers.
        assertThat(sm2.isOpen()).isEqualTo(true);
        sm2.abort();
        assertThat(future2).isCompletedExceptionally();
        assertThatThrownBy(future2::get).hasCauseExactlyInstanceOf(CancelledSubscriptionException.class);
        duplicator.close();
    }

    @Test
    public void closeMulticastStreamFactory() {
        final DefaultStreamMessage<String> publisher = new DefaultStreamMessage<>();
        final AbstractStreamMessageDuplicator<String, StreamMessage<String>> duplicator =
                new StreamMessageDuplicator(publisher);

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
            MulticastStream(StreamMessage<? extends String> delegate) {
                super(delegate);
            }
        }
    }

    private static class StringSubscriber implements Subscriber<String>, BiConsumer<Void, Throwable> {

        private CompletableFuture<String> future;
        private StringBuffer sb = new StringBuffer();
        private long demand;
        private Subscription subscription;

        StringSubscriber(CompletableFuture<String> future, long demand) {
            this.future = future;
            this.demand = demand;
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            s.request(demand);
        }

        @Override
        public void onNext(String s) {
            sb.append(s);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}

        @Override
        public void accept(Void aVoid, Throwable cause) {
            if (cause != null) {
                future.completeExceptionally(cause);
            } else {
                future.complete(sb.toString());
            }
        }

        void requestAnother() {
            subscription.request(1);
        }
    }
}
