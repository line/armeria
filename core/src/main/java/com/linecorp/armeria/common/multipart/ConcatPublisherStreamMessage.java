/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.multipart;

import static com.linecorp.armeria.internal.common.stream.StreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.internal.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

/**
 * Relay items in order from a {@link StreamMessage} of {@link StreamMessage} as a single {@link StreamMessage}
 * source.
 */
final class ConcatPublisherStreamMessage<T> implements StreamMessage<T> {

    private final StreamMessage<? extends StreamMessage<? extends T>> sources;

    @Nullable
    private volatile OuterSubscriber<T> outerSubscriber;

    ConcatPublisherStreamMessage(StreamMessage<? extends StreamMessage<? extends T>> sources) {
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

        final StreamMessage<? extends T> currentPublisher = outerSubscriber.currentPublisher;
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

        final InnerSubscriber<T> innerSubscriber = new InnerSubscriber<>(subscriber, options);
        final OuterSubscriber<T> outerSubscriber = new OuterSubscriber<>(innerSubscriber, executor);
        this.outerSubscriber = outerSubscriber;
        subscriber.onSubscribe(innerSubscriber);
        sources.subscribe(outerSubscriber, executor, options);
    }

    @Override
    public void abort() {
        sources.abort();
    }

    @Override
    public void abort(Throwable cause) {
        sources.abort(cause);
    }

    private static final class OuterSubscriber<T> implements Subscriber<StreamMessage<? extends T>> {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<OuterSubscriber, Subscription> upstreamUpdater =
                AtomicReferenceFieldUpdater.newUpdater(OuterSubscriber.class, Subscription.class, "upstream");

        private final InnerSubscriber<T> innerSubscriber;
        private final EventExecutor executor;

        private boolean completed;
        private boolean inSubscribe;

        @Nullable
        private volatile StreamMessage<? extends T> currentPublisher;
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
            if (upstreamUpdater.compareAndSet(this, null, subscription)) {
                nextSource();
            } else {
                subscription.cancel();
            }
        }

        @Override
        public void onNext(StreamMessage<? extends T> publisher) {
            requireNonNull(publisher, "publisher");
            inSubscribe = true;
            currentPublisher = publisher;
            publisher.subscribe(innerSubscriber, executor, innerSubscriber.options);
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");
            if (completed) {
                return;
            }
            completed = true;
            innerSubscriber.onError(cause);
        }

        @Override
        public void onComplete() {
            if (completed) {
                return;
            }

            // If 'innerSubscriber' is not complete, 'downstream' will be completed
            // when 'innerSubscriber' receives `onComplete` signal.
            completed = true;
            if (!inSubscribe && innerSubscriber.completed) {
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

        private static final long serialVersionUID = -676372622418720676L;

        private Subscriber<? super T> downstream;
        private final SubscriptionOption[] options;

        @Nullable
        private OuterSubscriber<T> outerSubscriber;

        private volatile boolean completed = true;
        // Updated via cancelledUpdater
        private volatile int cancelled;

        InnerSubscriber(Subscriber<? super T> downstream, SubscriptionOption[] options) {
            this.downstream = downstream;
            this.options = options;
        }

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
            completed = false;
            setSubscription(subscription);
            outerSubscriber.inSubscribe = false;
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
        public void onError(Throwable throwable) {
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (isCancelled()) {
                return;
            }
            completed = true;
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
                if (outerSubscriber != null && outerSubscriber.upstream != null) {
                    outerSubscriber.upstream.cancel();
                }
                super.cancel();
                if (!completed && containsNotifyCancellation(options)) {
                    downstream.onError(CancelledSubscriptionException.get());
                }
                downstream = NoopSubscriber.get();
            }
        }

        private boolean isCancelled() {
            return cancelled == 1;
        }
    }
}
