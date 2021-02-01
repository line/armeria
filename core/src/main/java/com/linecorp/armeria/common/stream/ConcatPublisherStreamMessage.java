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

import static com.linecorp.armeria.common.stream.StreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.util.concurrent.EventExecutor;

/**
 * Relay items in order from a {@link StreamMessage} of {@link StreamMessage}s as a single {@link StreamMessage}
 * source.
 */
final class ConcatPublisherStreamMessage<T> implements StreamMessage<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<ConcatPublisherStreamMessage, OuterSubscriber>
            outerSubscriberUpdater = AtomicReferenceFieldUpdater
            .newUpdater(ConcatPublisherStreamMessage.class, OuterSubscriber.class, "outerSubscriber");

    private final StreamMessage<? extends Publisher<? extends T>> sources;

    @Nullable
    private volatile OuterSubscriber<T> outerSubscriber;

    ConcatPublisherStreamMessage(StreamMessage<? extends Publisher<? extends T>> sources) {
        this.sources = sources;
    }

    @Override
    public boolean isOpen() {
        return sources.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    @Override
    public long demand() {
        final OuterSubscriber<T> outerSubscriber = this.outerSubscriber;
        if (outerSubscriber == null) {
            return 0;
        }

        final StreamMessage<? extends T> currentPublisher = outerSubscriber.currentStreamMessage;
        if (currentPublisher == null) {
            return 0;
        }

        return currentPublisher.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return sources.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, EMPTY_OPTIONS);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        final InnerSubscriber<T> innerSubscriber = new InnerSubscriber<>(subscriber, options, this);
        final OuterSubscriber<T> outerSubscriber = new OuterSubscriber<>(innerSubscriber, executor);
        if (outerSubscriberUpdater.compareAndSet(this, null, outerSubscriber)) {
            subscriber.onSubscribe(innerSubscriber);
            sources.subscribe(outerSubscriber, executor, options);
        } else {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(new IllegalStateException("subscribed by other subscriber already"));
        }
    }

    @Override
    public void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        sources.abort(cause);
        final OuterSubscriber<T> outerSubscriber = this.outerSubscriber;
        if (outerSubscriber != null) {
            outerSubscriber.abort(cause);
        }
    }

    private static final class OuterSubscriber<T> implements Subscriber<Publisher<? extends T>> {

        private final InnerSubscriber<T> innerSubscriber;
        private final EventExecutor executor;

        private boolean completed;
        private boolean inInnerOnSubscribe;

        @Nullable
        private volatile StreamMessage<? extends T> currentStreamMessage;
        @Nullable
        private volatile Subscription upstream;

        OuterSubscriber(InnerSubscriber<T> innerSubscriber, EventExecutor executor) {
            this.innerSubscriber = innerSubscriber;
            this.executor = executor;
            innerSubscriber.setOuterSubscriber(this);
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (upstream == null) {
                upstream = subscription;
                subscription.request(1);
            } else {
                subscription.cancel();
            }
        }

        @Override
        public void onNext(Publisher<? extends T> publisher) {
            requireNonNull(publisher, "publisher");
            final StreamMessage<? extends T> streamMessage = StreamMessage.of(publisher);
            currentStreamMessage = streamMessage;
            inInnerOnSubscribe = true;
            streamMessage.subscribe(innerSubscriber, executor, innerSubscriber.options);
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");
            if (completed) {
                return;
            }
            completed = true;
            innerSubscriber.onError(cause);
            abort(cause);
        }

        private void abort(Throwable cause) {
            final StreamMessage<? extends T> currentStreamMessage = this.currentStreamMessage;
            if (currentStreamMessage != null) {
                currentStreamMessage.abort(cause);
            }
        }

        @Override
        public void onComplete() {
            if (completed) {
                return;
            }

            // If 'innerSubscriber' is not complete, 'downstream' will be completed
            // when 'innerSubscriber' receives `onComplete` signal.
            completed = true;
            if (!inInnerOnSubscribe && innerSubscriber.currentPublisherCompleted) {
                innerSubscriber.onComplete();
            }
        }

        void nextSource() {
            upstream.request(1);
        }
    }

    private static final class InnerSubscriber<T> extends SubscriptionArbiter implements Subscriber<T> {

        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<InnerSubscriber> cancelledUpdater =
                AtomicIntegerFieldUpdater.newUpdater(InnerSubscriber.class, "cancelled");

        private Subscriber<? super T> downstream;
        private final SubscriptionOption[] options;
        private final ConcatPublisherStreamMessage<T> publisher;

        @Nullable
        private OuterSubscriber<T> outerSubscriber;

        private volatile boolean currentPublisherCompleted = true;
        // Updated via cancelledUpdater
        private volatile int cancelled;
        // Happen-Before is guaranteed in cancel() because 'cancelled' was written before.
        private boolean error;

        InnerSubscriber(Subscriber<? super T> downstream, SubscriptionOption[] options,
                        ConcatPublisherStreamMessage<T> publisher) {
            this.downstream = downstream;
            this.options = options;
            this.publisher = publisher;
        }

        /**
         * Sets the {@link OuterSubscriber}.
         * This should be set before subscribing to upstream.
         */
        void setOuterSubscriber(OuterSubscriber<T> outerSubscriber) {
            this.outerSubscriber = outerSubscriber;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (isCancelled()) {
                subscription.cancel();
                return;
            }
            // Reset 'completed' to subscribe to new Publisher
            currentPublisherCompleted = false;
            setUpstreamSubscription(subscription);
            outerSubscriber.inInnerOnSubscribe = false;
        }

        @Override
        public void onNext(T item) {
            requireNonNull(item, "item");
            if (isCancelled()) {
                return;
            }
            downstream.onNext(item);
            produced(1);
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");
            if (isCancelled()) {
                return;
            }

            if (error) {
                return;
            }
            error = true;

            downstream.onError(cause);
            publisher.abort(cause);
        }

        @Override
        public void onComplete() {
            if (isCancelled()) {
                return;
            }
            currentPublisherCompleted = true;
            if (outerSubscriber.completed) {
                downstream.onComplete();
            } else {
                outerSubscriber.nextSource();
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                downstream.onError(new IllegalArgumentException(
                        "Rule §3.9 violated: non-positive requests are forbidden"));
                return;
            }
            if (isCancelled()) {
                return;
            }
            super.request(n);
        }

        @Override
        public void cancel() {
            if (cancelledUpdater.compareAndSet(this, 0, 1)) {
                if (outerSubscriber != null) {
                    final Subscription upstream = outerSubscriber.upstream;
                    if (upstream != null) {
                        upstream.cancel();
                    }
                }
                super.cancel();
                final CancelledSubscriptionException cause = CancelledSubscriptionException.get();
                publisher.abort(cause);
                if (containsNotifyCancellation(options) && !currentPublisherCompleted && !error) {
                    downstream.onError(cause);
                }
                downstream = NoopSubscriber.get();
            }
        }

        private boolean isCancelled() {
            return cancelled != 0;
        }
    }
}
