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
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
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
 */
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Maps the upstream items via a {@link Function}.
 * @param <T> the upstream value type
 * @param <R> the result value type
 */
final class MultiMapperPublisher<T, R> implements Multi<R> {

    // Forked from https://github.com/oracle/helidon/blob/d7a465172789e30c414fc69dd174ab05e2c94000/common/reactive/src/main/java/io/helidon/common/reactive/MultiMapperPublisher.java

    private final Publisher<T> source;

    private final Function<? super T, ? extends R> mapper;

    MultiMapperPublisher(Publisher<T> source, Function<? super T, ? extends R> mapper) {
        this.source = source;
        this.mapper = mapper;
    }

    @Override
    public void subscribe(Subscriber<? super R> subscriber) {
        source.subscribe(new MapperSubscriber<>(subscriber, mapper));
    }

    static final class MapperSubscriber<T, R> implements Subscriber<T>, Subscription {

        private final Subscriber<? super R> downstream;

        private final Function<? super T, ? extends R> mapper;

        private Subscription upstream;

        MapperSubscriber(Subscriber<? super R> downstream, Function<? super T, ? extends R> mapper) {
            this.downstream = downstream;
            this.mapper = mapper;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            SubscriptionHelper.validate(upstream, subscription);
            upstream = subscription;
            downstream.onSubscribe(this);
        }

        @Override
        public void onNext(T item) {
            // in case the upstream doesn't stop immediately after a failed mapping
            final Subscription s = upstream;
            if (s != SubscriptionHelper.CANCELED) {
                final R result;

                try {
                    result = requireNonNull(mapper.apply(item), "The mapper returned a null value.");
                } catch (Throwable ex) {
                    s.cancel();
                    onError(ex);
                    return;
                }

                downstream.onNext(result);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // if mapper.map fails above, the upstream may still emit an onError without request
            if (upstream != SubscriptionHelper.CANCELED) {
                upstream = SubscriptionHelper.CANCELED;
                downstream.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            // if mapper.map fails above, the upstream may still emit an onComplete without request
            if (upstream != SubscriptionHelper.CANCELED) {
                upstream = SubscriptionHelper.CANCELED;
                downstream.onComplete();
            }
        }

        @Override
        public void request(long n) {
            upstream.request(n);
        }

        @Override
        public void cancel() {
            upstream.cancel();
            upstream = SubscriptionHelper.CANCELED;
        }
    }
}
