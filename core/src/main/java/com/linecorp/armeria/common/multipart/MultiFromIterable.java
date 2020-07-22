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

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Publisher from iterable, implemented as trampoline stack-less recursion.
 *
 * @param <T> item type
 */
final class MultiFromIterable<T> implements Multi<T> {

    // Forked from https://github.com/oracle/helidon/blob/269608534a1d5d99b7cd65f51f878398ee07ca6d/common/reactive/src/main/java/io/helidon/common/reactive/MultiFromIterable.java

    private final Iterable<? extends T> iterable;

    MultiFromIterable(Iterable<? extends T> iterable) {
        requireNonNull(iterable, "iterable");
        this.iterable = iterable;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");

        final Iterator<? extends T> iterator;
        final boolean hasFirst;
        try {
            iterator = iterable.iterator();
            hasFirst = iterator.hasNext();
        } catch (Throwable ex) {
            subscriber.onSubscribe(NoopSubscription.INSTANCE);
            subscriber.onError(ex);
            return;
        }

        if (!hasFirst) {
            subscriber.onSubscribe(NoopSubscription.INSTANCE);
            subscriber.onComplete();
            return;
        }

        subscriber.onSubscribe(new IteratorSubscription<>(subscriber, iterator));
    }

    static final class IteratorSubscription<T> extends AtomicLong implements Subscription {

        private static final long serialVersionUID = 487425833923970958L;

        private static final int NORMAL_CANCEL = 1;
        private static final int BAD_REQUEST = 2;

        private final Subscriber<? super T> downstream;

        @Nullable
        private Iterator<T> iterator;

        private volatile int cancelled;

        IteratorSubscription(Subscriber<? super T> downstream, Iterator<T> iterator) {
            this.downstream = downstream;
            this.iterator = iterator;
        }

        @Override
        public void request(long n) {
            if (n <= 0L) {
                cancelled = BAD_REQUEST;
                n = 1; // for cleanup
            }

            if (SubscriptionHelper.addRequest(this, n) != 0L) {
                return;
            }

            long emitted = 0L;
            final Subscriber<? super T> downstream = this.downstream;

            for (;;) {
                while (emitted != n) {
                    final int isCancelled = cancelled;
                    if (isCancelled != 0) {
                        iterator = null;
                        if (isCancelled == BAD_REQUEST) {
                            downstream.onError(new IllegalArgumentException(
                                    "Rule §3.9 violated: non-positive request amount is forbidden"));
                        }
                        return;
                    }

                    final T value;

                    try {
                        value = requireNonNull(iterator.next(),
                                "The iterator returned a null value");
                    } catch (Throwable ex) {
                        iterator = null;
                        cancelled = NORMAL_CANCEL;
                        downstream.onError(ex);
                        return;
                    }

                    if (cancelled != 0) {
                        continue;
                    }

                    downstream.onNext(value);

                    if (cancelled != 0) {
                        continue;
                    }

                    final boolean hasNext;

                    try {
                        hasNext = iterator.hasNext();
                    } catch (Throwable ex) {
                        iterator = null;
                        cancelled = NORMAL_CANCEL;
                        downstream.onError(ex);
                        return;
                    }

                    if (cancelled != 0) {
                        continue;
                    }

                    if (!hasNext) {
                        iterator = null;
                        downstream.onComplete();
                        return;
                    }

                    emitted++;
                }

                n = get();
                if (n == emitted) {
                    n = SubscriptionHelper.produced(this, emitted);
                    if (n == 0L) {
                        return;
                    }
                    emitted = 0L;
                }
            }
        }

        @Override
        public void cancel() {
            cancelled = NORMAL_CANCEL;
            request(1); // for cleanup
        }
    }
}
