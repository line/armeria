/*
 * Copyright 2016 LINE Corporation
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage.AbortableSubscriber;

import io.netty.buffer.ByteBufHolder;

class PublisherBasedStreamMessageTest {

    /**
     * Tests if {@link PublisherBasedStreamMessage#abort()} cancels the {@link Subscription}, and tests if
     * the abort operation is idempotent.
     */
    @Test
    void testAbortWithEarlyOnSubscribe() {
        final AbortTest test = new AbortTest();
        test.prepare();
        test.invokeOnSubscribe();
        test.abortAndAwait();
        test.verify();

        // Try to abort again, which should do nothing.
        test.abortAndAwait();
        test.verify();
    }

    /**
     * Tests if {@link PublisherBasedStreamMessage#abort()} cancels the {@link Subscription} even if
     * {@link Subscriber#onSubscribe(Subscription)} was invoked by the delegate {@link Publisher} after
     * {@link PublisherBasedStreamMessage#abort()} is called.
     */
    @Test
    void testAbortWithLateOnSubscribe() {
        final AbortTest test = new AbortTest();
        test.prepare();
        test.abort();
        test.invokeOnSubscribe();
        test.awaitAbort();
        test.verify();
    }

    /**
     * Tests if {@link PublisherBasedStreamMessage#abort()} prohibits further subscription.
     */
    @Test
    void testAbortWithoutSubscriber() {
        @SuppressWarnings("unchecked")
        final Publisher<Integer> delegate = mock(Publisher.class);
        final PublisherBasedStreamMessage<Integer> p = new PublisherBasedStreamMessage<>(delegate);
        p.abort();

        // Publisher should not be involved at all because we are aborting without subscribing.
        verify(delegate, never()).subscribe(any());
    }

    @Test
    void notifyCancellation() {
        final DefaultStreamMessage<ByteBufHolder> delegate = new DefaultStreamMessage<>();
        final PublisherBasedStreamMessage<ByteBufHolder> p = new PublisherBasedStreamMessage<>(delegate);
        SubscriptionOptionTest.notifyCancellation(p);
    }

    @Test
    void cancellationIsNotPropagatedByDefault() {
        final DefaultStreamMessage<Integer> delegate = new DefaultStreamMessage<>();
        final PublisherBasedStreamMessage<Integer> p = new PublisherBasedStreamMessage<>(delegate);

        p.subscribe(new Subscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.cancel();
            }

            @Override
            public void onNext(Integer integer) {
                fail();
            }

            @Override
            public void onError(Throwable t) {
                fail();
            }

            @Override
            public void onComplete() {
                fail();
            }
        });

        // We do call onError(t) first before completing the future.
        await().untilAsserted(() -> assertThat(p.completionFuture().isCompletedExceptionally()));
    }

    private static final class AbortTest {
        private PublisherBasedStreamMessage<Integer> publisher;
        private AbortableSubscriber subscriberWrapper;
        private Subscription subscription;

        AbortTest prepare() {
            // Create a mock delegate Publisher which will be wrapped by PublisherBasedStreamMessage.
            final ArgumentCaptor<AbortableSubscriber> subscriberCaptor =
                    ArgumentCaptor.forClass(AbortableSubscriber.class);
            @SuppressWarnings("unchecked")
            final Publisher<Integer> delegate = mock(Publisher.class);

            @SuppressWarnings("unchecked")
            final Subscriber<Integer> subscriber = mock(Subscriber.class);
            publisher = new PublisherBasedStreamMessage<>(delegate);

            // Subscribe.
            publisher.subscribe(subscriber);
            Mockito.verify(delegate).subscribe(subscriberCaptor.capture());

            // Capture the actual Subscriber implementation.
            subscriberWrapper = subscriberCaptor.getValue();

            // Prepare a mock Subscription.
            subscription = mock(Subscription.class);
            return this;
        }

        void invokeOnSubscribe() {
            // Call the subscriber.onSubscriber() with the mock Subscription to emulate
            // that the delegate triggers onSubscribe().
            subscriberWrapper.onSubscribe(subscription);
        }

        void abort() {
            publisher.abort();
        }

        void abortAndAwait() {
            abort();
            awaitAbort();
        }

        void awaitAbort() {
            assertThatThrownBy(() -> publisher.completionFuture().join())
                    .hasCauseInstanceOf(AbortedStreamException.class);
        }

        void verify() {
            // Ensure subscription.cancel() has been invoked.
            Mockito.verify(subscription).cancel();

            // Ensure completionFuture is complete exceptionally.
            assertThat(publisher.completionFuture()).isCompletedExceptionally();
            assertThatThrownBy(() -> publisher.completionFuture().get())
                    .hasCauseExactlyInstanceOf(AbortedStreamException.class);
        }
    }
}
