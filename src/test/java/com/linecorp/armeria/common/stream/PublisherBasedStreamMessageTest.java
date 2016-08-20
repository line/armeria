/*
 * Copyright 2016 LINE Corporation
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage.SubscriberWrapper;
import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage.SubscriptionWrapper;

public class PublisherBasedStreamMessageTest {

    /**
     * Tests if {@link PublisherBasedStreamMessage#subscribe(Subscriber)} works, and it disallows more than one
     * {@link Subscriber}s.
     */
    @Test
    public void testSubscription() {
        // Create a mock delegate Publisher which will be wrapped by PublisherBasedStreamMessage.
        final ArgumentCaptor<SubscriberWrapper> subscriberCaptor =
                ArgumentCaptor.forClass(SubscriberWrapper.class);
        @SuppressWarnings("unchecked")
        final Publisher<Integer> delegate = mock(Publisher.class);

        @SuppressWarnings("unchecked")
        final Subscriber<Integer> subscriber = mock(Subscriber.class);
        final PublisherBasedStreamMessage<Integer> p = new PublisherBasedStreamMessage<>(delegate);

        // First subscription attempt should be delegated to the delegate.
        p.subscribe(subscriber);
        verify(delegate).subscribe(subscriberCaptor.capture());

        // Second subscription attempt should fail.
        assertThatThrownBy(() -> p.subscribe(subscriber)).isInstanceOf(IllegalStateException.class);

        // onSubscribe() on the captured SubscriberWrapper should be delegated to the mock Subscriber.
        final SubscriberWrapper subscriberWrapper = subscriberCaptor.getValue();

        // Emulate that the delegate triggers onSubscribe().
        subscriberWrapper.onSubscribe(mock(Subscription.class));
        verify(subscriber).onSubscribe(any(SubscriptionWrapper.class));
    }

    /**
     * Tests if {@link Subscription#cancel()} completes {@link PublisherBasedStreamMessage#closeFuture()}
     * exceptionally.
     */
    @Test
    public void testCancelledSubscription() {
        // Create a mock delegate Publisher which will be wrapped by PublisherBasedStreamMessage.
        final ArgumentCaptor<SubscriberWrapper> subscriberCaptor =
                ArgumentCaptor.forClass(SubscriberWrapper.class);
        @SuppressWarnings("unchecked")
        final Publisher<Integer> delegate = mock(Publisher.class);
        final PublisherBasedStreamMessage<Integer> p = new PublisherBasedStreamMessage<>(delegate);

        // Subscribe and capture the Subscriber and Subscription.
        final ArgumentCaptor<SubscriptionWrapper> subscriptionCaptor =
                ArgumentCaptor.forClass(SubscriptionWrapper.class);
        @SuppressWarnings("unchecked")
        final Subscriber<Integer> subscriber = mock(Subscriber.class);

        p.subscribe(subscriber);
        verify(delegate).subscribe(subscriberCaptor.capture());

        // Emulate that the delegate triggers onSubscribe().
        final SubscriberWrapper subscriberWrapper = subscriberCaptor.getValue();
        subscriberWrapper.onSubscribe(mock(Subscription.class));
        verify(subscriber).onSubscribe(subscriptionCaptor.capture());

        // Cancel the subscription and check the status.
        subscriptionCaptor.getValue().cancel();
        assertThat(p.isOpen()).isEqualTo(false);
        assertThat(p.isEmpty()).isEqualTo(true);
        assertThat(p.closeFuture()).isCompletedExceptionally();
        assertThatThrownBy(() -> p.closeFuture().get())
                .hasCauseExactlyInstanceOf(CancelledSubscriptionException.class);
    }

    /**
     * Tests if {@link PublisherBasedStreamMessage#abort()} cancels the {@link Subscription}, and tests if
     * the abort operation is idempotent.
     */
    @Test
    public void testAbortWithEarlyOnSubscribe() {
        final AbortTest test = new AbortTest();
        test.prepare();
        test.invokeOnSubscribe();
        test.abort();
        test.verify();

        // Try to abort again, which should do nothing.
        test.abort();
        test.verify();
    }

    /**
     * Tests if {@link PublisherBasedStreamMessage#abort()} cancels the {@link Subscription} even if
     * {@link Subscriber#onSubscribe(Subscription)} was invoked by the delegate {@link Publisher} after
     * {@link PublisherBasedStreamMessage#abort()} is called.
     */
    @Test
    public void testAbortWithLateOnSubscribe() {
        final AbortTest test = new AbortTest();
        test.prepare();
        test.abort();
        test.invokeOnSubscribe();
        test.verify();
    }

    /**
     * Tests if {@link PublisherBasedStreamMessage#abort()} prohibits further subscription.
     */
    @Test
    public void testAbortWithoutSubscriber() {
        @SuppressWarnings("unchecked")
        final Publisher<Integer> delegate = mock(Publisher.class);
        final PublisherBasedStreamMessage<Integer> p = new PublisherBasedStreamMessage<>(delegate);
        p.abort();

        // Publisher should not be involved at all because we are aborting without subscribing.
        verify(delegate, never()).subscribe(any());

        // Attempting to subscribe after abort() should fail.
        @SuppressWarnings("unchecked")
        final Subscriber<Integer> subscriber = mock(Subscriber.class);
        assertThatThrownBy(() -> p.subscribe(subscriber)).isInstanceOf(IllegalStateException.class);
    }

    private static final class AbortTest {
        private PublisherBasedStreamMessage<Integer> p;
        private SubscriberWrapper subscriberWrapper;
        private Subscription subscription;

        AbortTest prepare() {
            // Create a mock delegate Publisher which will be wrapped by PublisherBasedStreamMessage.
            final ArgumentCaptor<SubscriberWrapper> subscriberCaptor =
                    ArgumentCaptor.forClass(SubscriberWrapper.class);
            @SuppressWarnings("unchecked")
            final Publisher<Integer> delegate = mock(Publisher.class);

            @SuppressWarnings("unchecked")
            final Subscriber<Integer> subscriber = mock(Subscriber.class);
            p = new PublisherBasedStreamMessage<>(delegate);

            // Subscribe.
            p.subscribe(subscriber);
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
            p.abort();
        }

        void verify() {
            // Ensure subscription.cancel() has been invoked.
            Mockito.verify(subscription).cancel();

            // Ensure closeFuture is complete exceptionally.
            assertThat(p.closeFuture()).isCompletedExceptionally();
            assertThatThrownBy(() -> p.closeFuture().get())
                    .hasCauseExactlyInstanceOf(CancelledSubscriptionException.class);
        }
    }
}
