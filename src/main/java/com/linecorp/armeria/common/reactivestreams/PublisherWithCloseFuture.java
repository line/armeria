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

package com.linecorp.armeria.common.reactivestreams;

import static java.util.Objects.requireNonNull;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Adapts a {@link Publisher} into a {@link RichPublisher}.
 *
 * @param <T> the type of element signaled
 */
public class PublisherWithCloseFuture<T> implements RichPublisher<T> {

    private final Publisher<? extends T> publisher;
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private final Queue<SubscriberImpl<T>> subscribers = new ConcurrentLinkedQueue<>();
    private volatile boolean publishedAny;
    private volatile boolean aborted;

    /**
     * Creates a new instance with the specified delegate {@link Publisher}.
     */
    public PublisherWithCloseFuture(Publisher<? extends T> publisher) {
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
        if (aborted) {
            throw new IllegalStateException("cannot subscribe to an aborted publisher");
        }
        final SubscriberImpl<T> s = new SubscriberImpl<T>(subscriber, executor);
        publisher.subscribe(s);
        subscribers.add(s);
    }

    @Override
    public void abort() {
        aborted = true;
        for (;;) {
            SubscriberImpl<T> s = subscribers.poll();
            if (s == null) {
                break;
            }

            s.abort();
        }
    }

    @Override
    public CompletableFuture<Void> closeFuture() {
        return closeFuture;
    }

    private final class SubscriberImpl<V> implements Subscriber<V> {
        private final Subscriber<? super V> subscriber;
        private final Executor executor;
        private volatile Subscription subscription;

        SubscriberImpl(Subscriber<? super V> subscriber, Executor executor) {
            this.subscriber = subscriber;
            this.executor = executor;
        }

        @Override
        public void onSubscribe(Subscription s) {
            final Executor executor = this.executor;
            subscription = s;
            if (executor == null) {
                subscriber.onSubscribe(s);
            } else {
                executor.execute(() -> subscriber.onSubscribe(s));
            }

        }

        void abort() {
            final Subscription subscription = this.subscription;
            if (subscription == null) {
                return;
            }

            subscription.cancel();
        }

        @Override
        public void onNext(V obj) {
            publishedAny = true;
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
                CompletableFuture<Void> closeFuture = PublisherWithCloseFuture.this.closeFuture;
                if (closeFuture != null) {
                    closeFuture.completeExceptionally(cause);
                }
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
                CompletableFuture<Void> closeFuture = PublisherWithCloseFuture.this.closeFuture;
                if (closeFuture != null) {
                    closeFuture.complete(null);
                }
            }
        }
    }
}
