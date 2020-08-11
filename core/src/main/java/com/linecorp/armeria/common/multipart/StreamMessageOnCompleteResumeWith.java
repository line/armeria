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
/*
 * Copyright (c)  2020 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * If the completes, switch to a generated Publisher and relay its signals then on.
 */
final class StreamMessageOnCompleteResumeWith<T> extends SimpleStreamMessage<T> {

    // Forked from https://github.com/oracle/helidon/blob/0325cae20e68664da0f518ea2d803b9dd211a7b5/common/reactive/src/main/java/io/helidon/common/reactive/MultiOnCompleteResumeWith.java

    private final StreamMessage<? extends T> source;
    private final StreamMessage<? extends T> fallbackPublisher;

    StreamMessageOnCompleteResumeWith(StreamMessage<? extends T> source,
                                      StreamMessage<? extends T> fallbackPublisher) {
        this.source = requireNonNull(source, "source");
        this.fallbackPublisher = requireNonNull(fallbackPublisher, "fallbackPublisher");
    }

    @Override
    public boolean isOpen() {
        return source.isOpen() || fallbackPublisher.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return source.isEmpty() && fallbackPublisher.isEmpty();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return fallbackPublisher.whenComplete();
    }

    @Override
    void subscribe(Subscriber<? super T> subscriber, boolean notifyCancellation) {
        requireNonNull(subscriber, "subscriber");
        source.subscribe(
                new OnCompleteResumeWithSubscriber<>(subscriber, fallbackPublisher, notifyCancellation));
    }

    @Override
    public void abort() {
        source.abort();
        fallbackPublisher.abort();
    }

    @Override
    public void abort(Throwable cause) {
        source.abort(cause);
        fallbackPublisher.abort(cause);
    }

    static final class OnCompleteResumeWithSubscriber<T> implements Subscriber<T>, Subscription {

        private final Subscriber<? super T> downstream;
        private final StreamMessage<? extends T> fallbackPublisher;
        private final AtomicLong requested;
        private final FallbackSubscriber<T> fallbackSubscriber;
        private final boolean notifyCancellation;

        private long received;

        @Nullable
        private Subscription upstream;

        OnCompleteResumeWithSubscriber(Subscriber<? super T> downstream,
                                       StreamMessage<? extends T> fallbackPublisher,
                                       boolean notifyCancellation) {
            this.downstream = downstream;
            this.fallbackPublisher = fallbackPublisher;
            requested = new AtomicLong();
            fallbackSubscriber = new FallbackSubscriber<>(downstream, requested);
            this.notifyCancellation = notifyCancellation;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (upstream != null) {
                subscription.cancel();
                throw new IllegalStateException("Subscription already set.");
            }
            upstream = subscription;
            downstream.onSubscribe(this);
        }

        @Override
        public void onNext(T item) {
            received++;
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            upstream = SubscriptionHelper.CANCELLED;
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            upstream = SubscriptionHelper.CANCELLED;
            final long p = received;
            if (p != 0L) {
                SubscriptionHelper.produced(requested, p);
            }

            final Publisher<? extends T> publisher;

            try {
                publisher = requireNonNull(fallbackPublisher,
                                           "The fallback function returned a null Publisher");
            } catch (Throwable ex) {
                downstream.onError(ex);
                return;
            }

            publisher.subscribe(fallbackSubscriber);
        }

        @Override
        public void request(long n) {
            if (n <= 0L) {
                downstream.onError(new IllegalArgumentException("Rule §3.9 violated: " +
                                                                "non-positive requests are forbidden"));
            } else {
                SubscriptionHelper.deferredRequest(fallbackSubscriber, requested, n);
                upstream.request(n);
            }
        }

        @Override
        public void cancel() {
            if (notifyCancellation && upstream != SubscriptionHelper.CANCELLED) {
                downstream.onError(CancelledSubscriptionException.get());
            }
            upstream.cancel();
            SubscriptionHelper.cancel(fallbackSubscriber);
        }

        static final class FallbackSubscriber<T> extends AtomicReference<Subscription>
                implements Subscriber<T> {

            private static final long serialVersionUID = -6724536079209262926L;

            private final Subscriber<? super T> downstream;
            private final AtomicLong requested;

            FallbackSubscriber(Subscriber<? super T> downstream, AtomicLong requested) {
                this.downstream = downstream;
                this.requested = requested;
            }

            @Override
            public void onSubscribe(Subscription subscription) {
                SubscriptionHelper.deferredSetOnce(this, requested, subscription);
            }

            @Override
            public void onNext(T item) {
                downstream.onNext(item);
            }

            @Override
            public void onError(Throwable throwable) {
                downstream.onError(throwable);
            }

            @Override
            public void onComplete() {
                downstream.onComplete();
            }
        }
    }
}
