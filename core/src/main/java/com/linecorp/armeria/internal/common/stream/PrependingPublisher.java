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

package com.linecorp.armeria.internal.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.NoopSubscriber;

public final class PrependingPublisher<T> implements Publisher<T> {

    private final T first;
    private final Publisher<? extends T> rest;

    public PrependingPublisher(T first, Publisher<? extends T> rest) {
        this.first = first;
        this.rest = rest;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        final RestSubscriber<T> restSubscriber = new RestSubscriber<>(first, rest, subscriber);
        subscriber.onSubscribe(restSubscriber);
    }

    static final class RestSubscriber<T> implements Subscriber<T>, Subscription {

        @SuppressWarnings("rawtypes")
        private static final AtomicLongFieldUpdater<RestSubscriber> demandUpdater =
                AtomicLongFieldUpdater.newUpdater(RestSubscriber.class, "demand");

        private final T first;
        private final Publisher<? extends T> rest;
        private Subscriber<? super T> downstream;
        @Nullable
        private volatile Subscription upstream;
        private volatile long demand;
        private boolean firstSent;
        private boolean subscribed;
        private volatile boolean cancelled;

        RestSubscriber(T first, Publisher<? extends T> rest, Subscriber<? super T> downstream) {
            this.first = first;
            this.rest = rest;
            this.downstream = downstream;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                downstream.onError(new IllegalArgumentException("non-positive request signals are illegal"));
                return;
            }
            if (cancelled) {
                return;
            }
            for (;;) {
                final long demand = this.demand;
                final long newDemand = LongMath.saturatedAdd(demand, n);
                if (demandUpdater.compareAndSet(this, demand, newDemand)) {
                    if (demand > 0) {
                        return;
                    }
                    break;
                }
            }
            if (!firstSent) {
                firstSent = true;
                downstream.onNext(first);
                if (demand != Long.MAX_VALUE) {
                    demandUpdater.decrementAndGet(this);
                }
            }
            if (!subscribed) {
                subscribed = true;
                rest.subscribe(this);
            }
            if (demand == 0) {
                return;
            }
            @Nullable
            final Subscription upstream = this.upstream;
            if (upstream != null) {
                final long demand = this.demand;
                if (demand > 0) {
                    if (demandUpdater.compareAndSet(this, demand, 0)) {
                        upstream.request(demand);
                    }
                }
            }
        }

        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            downstream = NoopSubscriber.get();
            @Nullable
            final Subscription upstream = this.upstream;
            if (upstream != null) {
                upstream.cancel();
            }
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (cancelled) {
                subscription.cancel();
                return;
            }
            upstream = subscription;
            for (;;) {
                final long demand = this.demand;
                if (demand == 0) {
                    break;
                }
                if (demandUpdater.compareAndSet(this, demand, 0)) {
                    subscription.request(demand);
                }
            }
        }

        @Override
        public void onNext(T t) {
            requireNonNull(t, "element");
            downstream.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            requireNonNull(t, "throwable");
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }
    }
}
