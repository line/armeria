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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.annotations.VisibleForTesting;

/**
 * Adapts a {@link Publisher} into a {@link StreamMessage}.
 *
 * @param <T> the type of element signaled
 */
public class PublisherBasedStreamMessage<T> implements StreamMessage<T> {

    private static final AbortableSubscriber ABORTED_SUBSCRIBER = new AbortedSubscriber();

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<PublisherBasedStreamMessage, AbortableSubscriber>
            subscriberUpdater = AtomicReferenceFieldUpdater.newUpdater(
            PublisherBasedStreamMessage.class, AbortableSubscriber.class, "subscriber");

    private final Publisher<? extends T> publisher;
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    @SuppressWarnings("unused") // Updated only via subscriberUpdater.
    private volatile AbortableSubscriber subscriber;
    private volatile boolean publishedAny;

    /**
     * Creates a new instance with the specified delegate {@link Publisher}.
     */
    public PublisherBasedStreamMessage(Publisher<? extends T> publisher) {
        this.publisher = publisher;
    }

    /**
     * Returns the delegate {@link Publisher}.
     */
    protected Publisher<? extends T> delegate() {
        return publisher;
    }

    @Override
    public boolean isOpen() {
        return !closeFuture.isDone();
    }

    @Override
    public boolean isEmpty() {
        return !isOpen() && !publishedAny;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        subscribe0(subscriber, null);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, Executor executor) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        subscribe0(subscriber, executor);
    }

    private void subscribe0(Subscriber<? super T> subscriber, Executor executor) {
        final SubscriberWrapper s = new SubscriberWrapper(this, subscriber, executor);
        if (!subscriberUpdater.compareAndSet(this, null, s)) {
            if (this.subscriber == ABORTED_SUBSCRIBER) {
                throw new IllegalStateException("cannot subscribe to an aborted publisher");
            } else {
                throw new IllegalStateException(
                        "subscribed by other subscriber already: " + subscriber);
            }
        }

        publisher.subscribe(s);
    }

    @Override
    public void abort() {
        final AbortableSubscriber s = subscriberUpdater.getAndSet(this, ABORTED_SUBSCRIBER);
        if (s != null) {
            s.abort();
        }
    }

    @Override
    public CompletableFuture<Void> closeFuture() {
        return closeFuture;
    }

    private interface AbortableSubscriber extends Subscriber<Object> {
        void abort();
    }

    private static final class AbortedSubscriber implements AbortableSubscriber {
        @Override
        public void abort() {}

        @Override
        public void onSubscribe(Subscription s) {}

        @Override
        public void onNext(Object o) {}

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}
    }

    @VisibleForTesting
    static final class SubscriberWrapper implements AbortableSubscriber {
        private final PublisherBasedStreamMessage<?> parent;
        private final Subscriber<Object> subscriber;
        private final Executor executor;
        private boolean abortPending;
        private SubscriptionWrapper subscription;

        @SuppressWarnings("unchecked")
        SubscriberWrapper(PublisherBasedStreamMessage<?> parent,
                          Subscriber<?> subscriber, Executor executor) {
            this.parent = parent;
            this.subscriber = (Subscriber<Object>) subscriber;
            this.executor = executor;
        }

        @Override
        public void onSubscribe(Subscription s) {
            final boolean abortPending;
            final SubscriptionWrapper wrappedSubscription;
            synchronized (this) {
                wrappedSubscription = new SubscriptionWrapper(parent, executor, s);
                subscription = wrappedSubscription;
                abortPending = this.abortPending;
            }

            if (executor == null) {
                onSubscribe0(wrappedSubscription, abortPending);
            } else {
                executor.execute(() -> onSubscribe0(wrappedSubscription, abortPending));
            }
        }

        private void onSubscribe0(SubscriptionWrapper s, boolean abortPending) {
            try {
                subscriber.onSubscribe(s);
            } finally {
                if (abortPending) {
                    s.cancel();
                }
            }
        }

        @Override
        public void abort() {
            final Subscription subscription;
            synchronized (this) {
                subscription = this.subscription;
                if (subscription == null) {
                    // onSubscribe() was not invoked by Publisher yet; abort later.
                    abortPending = true;
                    return;
                }
            }

            final Executor executor = this.executor;
            if (executor == null) {
                subscription.cancel();
            } else {
                executor.execute(subscription::cancel);
            }
        }

        @Override
        public void onNext(Object obj) {
            parent.publishedAny = true;
            final Executor executor = this.executor;
            if (executor == null) {
                subscriber.onNext(obj);
            } else {
                executor.execute(() -> subscriber.onNext(obj));
            }
        }

        @Override
        public void onError(Throwable cause) {
            final Executor executor = this.executor;
            if (executor == null) {
                onError0(cause);
            } else {
                executor.execute(() -> onError0(cause));
            }
        }

        private void onError0(Throwable cause) {
            try {
                subscriber.onError(cause);
            } finally {
                parent.closeFuture().completeExceptionally(cause);
            }
        }

        @Override
        public void onComplete() {
            final Executor executor = this.executor;
            if (executor == null) {
                onComplete0();
            } else {
                executor.execute(this::onComplete0);
            }
        }

        private void onComplete0() {
            try {
                subscriber.onComplete();
            } finally {
                parent.closeFuture().complete(null);
            }
        }
    }

    @VisibleForTesting
    static final class SubscriptionWrapper implements Subscription {

        private final PublisherBasedStreamMessage<?> parent;
        private final Executor executor;
        private final Subscription s;

        SubscriptionWrapper(PublisherBasedStreamMessage<?> parent, Executor executor, Subscription s) {
            this.parent = parent;
            this.executor = executor;
            this.s = s;
        }

        @Override
        public void request(long n) {
            s.request(n);
        }

        @Override
        public void cancel() {
            try {
                s.cancel();
            } finally {
                if (executor == null) {
                    completeCloseFuture();
                } else {
                    executor.execute(this::completeCloseFuture);
                }
            }
        }

        private void completeCloseFuture() {
            parent.closeFuture().completeExceptionally(CancelledSubscriptionException.get());
        }
    }
}
