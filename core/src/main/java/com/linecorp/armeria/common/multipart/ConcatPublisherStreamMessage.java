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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * Relay items in order from a {@link StreamMessage} of {@link Publisher} as a single {@link StreamMessage}
 * source.
 */
final class ConcatPublisherStreamMessage<T> extends SimpleStreamMessage<T> {

    private final StreamMessage<? extends Publisher<? extends T>> sources;

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
    public CompletableFuture<Void> whenComplete() {
        return sources.whenComplete();
    }

    @Override
    public void abort() {
        sources.abort();
    }

    @Override
    public void abort(Throwable cause) {
        sources.abort(cause);
    }

    @Override
    void subscribe(Subscriber<? super T> subscriber, boolean notifyCancellation) {
        requireNonNull(subscriber, "subscriber");
        final InnerSubscriber<T> innerSubscriber = new InnerSubscriber<>(subscriber, notifyCancellation);
        final OuterSubscriber<T> outerSubscriber = new OuterSubscriber<>(innerSubscriber);
        innerSubscriber.setOuterSubscriber(outerSubscriber);
        subscriber.onSubscribe(innerSubscriber);
        sources.subscribe(outerSubscriber);
    }

    private static final class OuterSubscriber<T> implements Subscriber<Publisher<? extends T>> {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<OuterSubscriber, Subscription> upstreamUpdater =
                AtomicReferenceFieldUpdater.newUpdater(OuterSubscriber.class, Subscription.class, "upstream");

        private final InnerSubscriber<T> innerSubscriber;

        private boolean completed;

        @Nullable
        private volatile Subscription upstream;
        private volatile boolean inSubscribe;

        OuterSubscriber(InnerSubscriber<T> innerSubscriber) {
            this.innerSubscriber = innerSubscriber;
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
        public void onNext(Publisher<? extends T> publisher) {
            inSubscribe = true;
            publisher.subscribe(innerSubscriber);
        }

        @Override
        public void onError(Throwable cause) {
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

            // If inner subscribe is not complete, let complete when inner subscriber receiving `onComplete`
            // signal.
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

        private final Subscriber<? super T> downstream;
        private final boolean notifyCancellation;

        @Nullable
        private OuterSubscriber<T> outerSubscriber;

        private volatile boolean completed = true;
        // Updated via cancelledUpdater
        private volatile int cancelled;

        InnerSubscriber(Subscriber<? super T> downstream, boolean notifyCancellation) {
            this.downstream = downstream;
            this.notifyCancellation = notifyCancellation;
        }

        void setOuterSubscriber(OuterSubscriber<T> outerSubscriber) {
            this.outerSubscriber = outerSubscriber;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (isCancelled()) {
                subscription.cancel();
                return;
            }
            // Subscribe to new Publisher
            completed = false;
            setSubscription(subscription);
            outerSubscriber.inSubscribe = false;
        }

        @Override
        public void onNext(T item) {
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
                if (!completed && notifyCancellation) {
                    downstream.onError(CancelledSubscriptionException.get());
                }
            }
        }

        private boolean isCancelled() {
            return cancelled == 1;
        }
    }
}
