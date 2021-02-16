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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.util.concurrent.EventExecutor;

final class StreamMessageFilter<T> extends StreamMessageWrapper<T> {

    private final Predicate<? super T> predicate;

    StreamMessageFilter(StreamMessage<? extends T> source, Predicate<? super T> predicate) {
        super(source);
        this.predicate = requireNonNull(predicate, "predicate");
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        super.subscribe(new FilterSubscriber<>(subscriber, predicate), executor);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        super.subscribe(new FilterSubscriber<>(subscriber, predicate), executor, options);
    }

    private static final class FilterSubscriber<T> implements Subscriber<T>, Subscription {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<FilterSubscriber, Subscription> upstreamUpdater =
                AtomicReferenceFieldUpdater.newUpdater(FilterSubscriber.class, Subscription.class, "upstream");

        private final Subscriber<? super T> downstream;
        private final Predicate<? super T> predicate;

        @Nullable
        private volatile Subscription upstream;

        FilterSubscriber(Subscriber<? super T> downstream, Predicate<? super T> predicate) {
            requireNonNull(downstream, "downstream");
            this.downstream = downstream;
            this.predicate = predicate;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (!upstreamUpdater.compareAndSet(this, null, subscription)) {
                subscription.cancel();
                throw new IllegalStateException("Subscription already set!");
            }
            upstream = subscription;
            downstream.onSubscribe(this);
        }

        @Override
        public void onNext(T item) {
            final Subscription upstream = this.upstream;
            if (upstream != null) {
                final boolean pass;
                try {
                    pass = predicate.test(item);
                } catch (Throwable ex) {
                    upstream.cancel();
                    onError(ex);
                    return;
                }

                if (pass) {
                    downstream.onNext(item);
                } else {
                    upstream.request(1);
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (upstream != null) {
                upstream = null;
                downstream.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (upstream != null) {
                upstream = null;
                downstream.onComplete();
            }
        }

        @Override
        public void request(long n) {
            final Subscription s = upstream;
            if (s != null) {
                s.request(n);
            }
        }

        @Override
        public void cancel() {
            final Subscription s = upstream;
            upstream = null;
            if (s != null) {
                s.cancel();
            }
        }
    }
}
