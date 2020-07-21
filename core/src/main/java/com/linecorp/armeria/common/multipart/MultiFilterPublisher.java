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

import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

final class MultiFilterPublisher<T> implements Multi<T> {

    // Forked from https://github.com/oracle/helidon/blob/5005bd7ebd57f416586149679c12778c8abebac3/common/reactive/src/main/java/io/helidon/common/reactive/MultiFilterPublisher.java

    private final Multi<T> source;
    private final Predicate<? super T> predicate;

    MultiFilterPublisher(Multi<T> source, Predicate<? super T> predicate) {
        this.source = requireNonNull(source, "source");
        this.predicate = requireNonNull(predicate, "predicate");
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        source.subscribe(new FilterSubscriber<>(subscriber, predicate));
    }

    static final class FilterSubscriber<T> implements Subscriber<T>, Subscription {

        private final Subscriber<? super T> downstream;
        private final Predicate<? super T> predicate;

        @Nullable
        private Subscription upstream;

        FilterSubscriber(Subscriber<? super T> downstream, Predicate<? super T> predicate) {
            this.downstream = downstream;
            this.predicate = predicate;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (upstream != null) {
                subscription.cancel();
                throw new IllegalStateException("Subscription already set!");
            }
            upstream = requireNonNull(subscription, "subscription");
            downstream.onSubscribe(this);
        }

        @Override
        public void onNext(T item) {
            final Subscription s = upstream;
            if (s != null) {
                final boolean pass;
                try {
                    pass = predicate.test(item);
                } catch (Throwable ex) {
                    s.cancel();
                    onError(ex);
                    return;
                }

                if (pass) {
                    downstream.onNext(item);
                } else {
                    s.request(1L);
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
