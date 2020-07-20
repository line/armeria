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

import java.util.concurrent.atomic.AtomicInteger;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Relay items in order from subsequent Publishers as a single Multi source.
 */
final class MultiConcatArray<T> implements Multi<T> {

    // Forked from https://github.com/oracle/helidon/blob/28cb3e8a34bda691c035d21f90b6278c6a42007c/common/reactive/src/main/java/io/helidon/common/reactive/MultiConcatArray.java

    private final Publisher<? extends T>[] sources;

    MultiConcatArray(Publisher<? extends T>[] sources) {
        this.sources = sources;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        final ConcatArraySubscriber<T> parent = new ConcatArraySubscriber<>(subscriber, sources);
        subscriber.onSubscribe(parent);
        parent.nextSource();
    }

    private static final class ConcatArraySubscriber<T> extends SubscriptionArbiter implements Subscriber<T> {

        private static final long serialVersionUID = -9184116713095894096L;

        private final Subscriber<? super T> downstream;

        private final Publisher<? extends T>[] sources;

        private final AtomicInteger wip;

        private int index;

        private long produced;

        ConcatArraySubscriber(Subscriber<? super T> downstream, Publisher<? extends T>[] sources) {
            this.downstream = downstream;
            this.sources = sources;
            wip = new AtomicInteger();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            setSubscription(subscription);
        }

        @Override
        public void onNext(T item) {
            produced++;
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            final long produced = this.produced;
            if (produced != 0L) {
                this.produced = 0L;
                produced(produced);
            }
            nextSource();
        }

        void nextSource() {
            if (wip.getAndIncrement() == 0) {
                do {
                    if (index == sources.length) {
                        downstream.onComplete();
                    } else {
                        sources[index++].subscribe(this);
                    }
                } while (wip.decrementAndGet() != 0);
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                downstream.onError(new IllegalArgumentException(
                        "Rule §3.9 violated: non-positive requests are forbidden"));
            } else {
                super.request(n);
            }
        }
    }
}
