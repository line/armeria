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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * If the completes, switch to a generated Publisher and relay its signals then on.
 *
 * @param <T> the element type of the flows
 */
final class MultiOnCompleteResumeWith<T> implements Multi<T> {

    // Forked from https://github.com/oracle/helidon/blob/0325cae20e68664da0f518ea2d803b9dd211a7b5/common
    // /reactive/src/main/java/io/helidon/common/reactive/MultiOnCompleteResumeWith.java

    private final Multi<T> source;

    private final Publisher<? extends T> fallbackPublisher;

    MultiOnCompleteResumeWith(Multi<T> source, Publisher<? extends T> fallbackPublisher) {
        this.source = source;
        this.fallbackPublisher = fallbackPublisher;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        source.subscribe(new OnCompleteResumeWithSubscriber<>(subscriber, fallbackPublisher));
    }

    static final class OnCompleteResumeWithSubscriber<T> implements Subscriber<T>, Subscription {

        private final Subscriber<? super T> downstream;

        private final Publisher<? extends T> fallbackPublisher;

        @Nullable
        private Subscription upstream;

        private long received;

        private final AtomicLong requested;

        private final FallbackSubscriber<T> fallbackSubscriber;

        OnCompleteResumeWithSubscriber(Subscriber<? super T> downstream,
                                       Publisher<? extends T> fallbackPublisher) {
            this.downstream = downstream;
            this.fallbackPublisher = fallbackPublisher;
            requested = new AtomicLong();
            fallbackSubscriber = new FallbackSubscriber<>(downstream, requested);
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            SubscriptionHelper.validate(upstream, subscription);
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
            upstream = SubscriptionHelper.CANCELED;
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            upstream = SubscriptionHelper.CANCELED;
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
            upstream.cancel();
            SubscriptionHelper.cancel(fallbackSubscriber);
        }

        static final class FallbackSubscriber<T> extends AtomicReference<Subscription>
                implements Subscriber<T> {

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
