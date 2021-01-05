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

import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

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
        final RestSubscriber restSubscriber = new RestSubscriber(subscriber);
        rest.subscribe(restSubscriber);
    }

    final class RestSubscriber implements Subscriber<T>, Subscription {

        private Subscriber<? super T> downstream;
        private final AtomicLong demand = new AtomicLong();
        @Nullable
        private volatile Subscription upstream;
        @Nullable
        private volatile Throwable upstreamCause;
        private volatile boolean upstreamCompleted;
        private volatile boolean completed;
        private volatile boolean firstSent;

        RestSubscriber(Subscriber<? super T> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.upstream = subscription;
            downstream.onSubscribe(this);
        }

        @Override
        public void onNext(T t) {
            demand.decrementAndGet();
            downstream.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            // delay onError until the first piece is sent
            if (!firstSent) {
                upstreamCause = t;
            } else {
                downstream.onError(t);
            }
        }

        @Override
        public void onComplete() {
            // delay onComplete until the first piece is sent
            if (!firstSent) {
                upstreamCompleted = true;
            } else {
                downstream.onComplete();
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                downstream.onError(new IllegalArgumentException("non-positive request signals are illegal"));
                return;
            }
            if (completed) {
                return;
            }
            if (demand.getAndAdd(n) > 0) {
                return;
            }
            if (!firstSent) {
                firstSent = true;
                downstream.onNext(first);
                if (n < Long.MAX_VALUE) {
                    demand.decrementAndGet();
                }
            }
            if (demand.get() > 0) {
                if (upstreamCause != null) {
                    downstream.onError(upstreamCause);
                } else if (upstreamCompleted) {
                    completed = true;
                    downstream.onComplete();
                } else {
                    upstream.request(demand.get());
                }
            }
        }

        @Override
        public void cancel() {
            if (completed) {
                return;
            }
            completed = true;
            downstream = null;
            upstream.cancel();
        }
    }
}
