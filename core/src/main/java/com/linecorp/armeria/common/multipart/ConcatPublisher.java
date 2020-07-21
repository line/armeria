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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Concat streams to one.
 *
 * @param <T> item type
 */
final class ConcatPublisher<T> implements Multi<T> {

    // Forked from https://github.com/oracle/helidon/blob/000d470d3dd716828d67830d43c2a0c6adcbb3c4/common/reactive/src/main/java/io/helidon/common/reactive/ConcatPublisher.java

    private final Publisher<? extends T> firstPublisher;
    private final Publisher<? extends T> secondPublisher;

    /**
     * Creates new {@code ConcatPublisher}.
     *
     * @param firstPublisher  first stream
     * @param secondPublisher second stream
     */
    ConcatPublisher(Publisher<? extends T> firstPublisher, Publisher<? extends T> secondPublisher) {
        requireNonNull(firstPublisher, "firstPublisher");
        requireNonNull(secondPublisher, "secondPublisher");
        this.firstPublisher = firstPublisher;
        this.secondPublisher = secondPublisher;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        final ConcatCancelingSubscription<T> parent =
                new ConcatCancelingSubscription<>(subscriber, firstPublisher, secondPublisher);
        subscriber.onSubscribe(parent);
        parent.drain();
    }

    static final class ConcatCancelingSubscription<T> extends AtomicInteger implements Subscription {

        private static final long serialVersionUID = -1593224722447706944L;

        private final InnerSubscriber<T> inner1;
        private final InnerSubscriber<T> inner2;
        private final AtomicBoolean canceled;

        private int index;

        @Nullable
        private Publisher<? extends T> source1;
        @Nullable
        private Publisher<? extends T> source2;

        ConcatCancelingSubscription(Subscriber<? super T> subscriber,
                                    Publisher<? extends T> source1, Publisher<? extends T> source2) {
            inner1 = new InnerSubscriber<>(subscriber, this);
            inner2 = new InnerSubscriber<>(subscriber, this);
            canceled = new AtomicBoolean();
            this.source1 = source1;
            this.source2 = source2;
        }

        @Override
        public void request(long n) {
            SubscriptionHelper.deferredRequest(inner2, inner2.requested, n);
            SubscriptionHelper.deferredRequest(inner1, inner1.requested, n);
        }

        @Override
        public void cancel() {
            if (canceled.compareAndSet(false, true)) {
                SubscriptionHelper.cancel(inner1);
                SubscriptionHelper.cancel(inner2);
                drain();
            }
        }

        void drain() {
            if (getAndIncrement() != 0) {
                return;
            }

            int missed = 1;
            for (;;) {

                if (index == 0) {
                    index = 1;
                    final Publisher<? extends T> source = source1;
                    source1 = null;
                    source.subscribe(inner1);
                } else if (index == 1) {
                    index = 2;
                    final Publisher<? extends T> source = source2;
                    source2 = null;
                    if (inner1.produced != 0L) {
                        SubscriptionHelper.produced(inner2.requested, inner1.produced);
                    }
                    source.subscribe(inner2);
                } else if (index == 2) {
                    index = 3;
                    if (!canceled.get()) {
                        inner1.downstream.onComplete();
                    }
                }

                missed = addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
            }
        }

        static final class InnerSubscriber<T> extends AtomicReference<Subscription> implements Subscriber<T> {

            private static final long serialVersionUID = 3029954591185720794L;

            private final Subscriber<? super T> downstream;
            private final ConcatCancelingSubscription<T> parent;
            private final AtomicLong requested;

            private long produced;

            InnerSubscriber(Subscriber<? super T> downstream, ConcatCancelingSubscription<T> parent) {
                this.downstream = downstream;
                this.parent = parent;
                requested = new AtomicLong();
            }

            @Override
            public void onSubscribe(Subscription subscription) {
                requireNonNull(subscription, "s");
                SubscriptionHelper.deferredSetOnce(this, requested, subscription);
            }

            @Override
            public void onNext(T t) {
                if (get() != SubscriptionHelper.CANCELED) {
                    produced++;
                    downstream.onNext(t);
                }
            }

            @Override
            public void onError(Throwable t) {
                if (get() != SubscriptionHelper.CANCELED) {
                    lazySet(SubscriptionHelper.CANCELED);
                    downstream.onError(t);

                    parent.cancel();
                }
            }

            @Override
            public void onComplete() {
                if (get() != SubscriptionHelper.CANCELED) {
                    lazySet(SubscriptionHelper.CANCELED);
                    parent.drain();
                }
            }
        }
    }
}
