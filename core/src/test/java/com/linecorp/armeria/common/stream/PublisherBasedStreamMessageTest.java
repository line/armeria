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

import static com.linecorp.armeria.common.stream.StreamMessageTest.newPooledBuffer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage.AbortableSubscriber;

import io.netty.buffer.ByteBuf;

class PublisherBasedStreamMessageTest {

    /**
     * Tests if {@link PublisherBasedStreamMessage#abort()} cancels the {@link Subscription}, and tests if
     * the abort operation is idempotent.
     */
    @ParameterizedTest
    @ArgumentsSource(AbortCauseArgumentProvider.class)
    void testAbortWithEarlyOnSubscribe(@Nullable Throwable cause) {
        final AbortTest test = new AbortTest();
        test.prepare();
        test.invokeOnSubscribe();
        test.abortAndAwait(cause);
        test.verify(cause);

        // Try to abort again, which should do nothing.
        test.abortAndAwait(cause);
        test.verify(cause);
    }

    /**
     * Tests if {@link PublisherBasedStreamMessage#abort()} cancels the {@link Subscription} even if
     * {@link Subscriber#onSubscribe(Subscription)} was invoked by the delegate {@link Publisher} after
     * {@link PublisherBasedStreamMessage#abort()} is called.
     */
    @ParameterizedTest
    @ArgumentsSource(AbortCauseArgumentProvider.class)
    void testAbortWithLateOnSubscribe(@Nullable Throwable cause) {
        final AbortTest test = new AbortTest();
        test.prepare();
        test.abort(cause);
        test.invokeOnSubscribe();
        test.awaitAbort(cause);
        test.verify(cause);
    }

    /**
     * Tests if {@link PublisherBasedStreamMessage#abort()} prohibits further subscription.
     */
    @ParameterizedTest
    @ArgumentsSource(AbortCauseArgumentProvider.class)
    void testAbortWithoutSubscriber(@Nullable Throwable cause) {
        @SuppressWarnings("unchecked")
        final Publisher<Integer> delegate = mock(Publisher.class);
        final PublisherBasedStreamMessage<Integer> p = new PublisherBasedStreamMessage<>(delegate);
        if (cause == null) {
            p.abort();
        } else {
            p.abort(cause);
        }

        // Publisher should not be involved at all because we are aborting without subscribing.
        verify(delegate, only()).subscribe(any(AbortableSubscriber.class));
    }

    @Test
    void notifyCancellation() {
        final ByteBuf buf = newPooledBuffer();
        final DefaultStreamMessage<HttpData> delegate = new DefaultStreamMessage<>();
        delegate.write(HttpData.wrap(buf));
        final PublisherBasedStreamMessage<HttpData> p = new PublisherBasedStreamMessage<>(delegate);
        SubscriptionOptionTest.notifyCancellation(buf, p);
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
                fail("onNext() invoked unexpectedly");
            }

            @Override
            public void onError(Throwable t) {
                fail("onError() invoked unexpectedly");
            }

            @Override
            public void onComplete() {
                fail("onComplete() invoked unexpectedly");
            }
        });

        // We do call onError(t) first before completing the future.
        await().untilAsserted(() -> assertThat(p.whenComplete()).isCompletedExceptionally());
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

        void abort(@Nullable Throwable cause) {
            if (cause == null) {
                publisher.abort();
            } else {
                publisher.abort(cause);
            }
        }

        void abortAndAwait(@Nullable Throwable cause) {
            abort(cause);
            awaitAbort(cause);
        }

        void awaitAbort(@Nullable Throwable cause) {
            if (cause == null) {
                assertThatThrownBy(() -> publisher.whenComplete().join())
                        .hasCauseInstanceOf(AbortedStreamException.class);
            } else {
                assertThatThrownBy(() -> publisher.whenComplete().join())
                        .hasCauseInstanceOf(cause.getClass());
            }
        }

        void verify(@Nullable Throwable cause) {
            // Ensure subscription.cancel() has been invoked.
            Mockito.verify(subscription, timeout(3000)).cancel();

            // Ensure completionFuture is complete exceptionally.
            assertThat(publisher.whenComplete()).isCompletedExceptionally();
            if (cause == null) {
                assertThatThrownBy(() -> publisher.whenComplete().get())
                        .hasCauseExactlyInstanceOf(AbortedStreamException.class);
            } else {
                assertThatThrownBy(() -> publisher.whenComplete().get())
                        .hasCauseExactlyInstanceOf(cause.getClass());
            }
        }
    }
}
