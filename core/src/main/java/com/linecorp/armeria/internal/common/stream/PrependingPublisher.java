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
        final RestSubscriber restSubscriber = new RestSubscriber(subscriber);
        rest.subscribe(restSubscriber);
    }

    final class RestSubscriber implements Subscriber<T>, Subscription {

        private final Subscriber<? super T> subscriber;
        @Nullable
        private volatile Subscription subscription;
        @Nullable
        private volatile Throwable cause;
        private volatile boolean completed;
        private volatile boolean firstSent;

        RestSubscriber(Subscriber<? super T> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            subscriber.onSubscribe(this);
        }

        @Override
        public void onNext(T t) {
            subscriber.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            // delay onError until the first piece is sent
            if (!firstSent) {
                cause = t;
            } else {
                subscriber.onError(t);
            }
        }

        @Override
        public void onComplete() {
            // delay onComplete until the first piece is sent
            if (!firstSent) {
                completed = true;
            } else {
                subscriber.onComplete();
            }
        }

        @Override
        public void request(long n) {
            if (!firstSent) {
                subscriber.onNext(first);
                n--;
                firstSent = true;
            }
            if (n > 0) {
                if (cause != null) {
                    subscriber.onError(cause);
                } else if (completed) {
                    subscriber.onComplete();
                } else {
                    subscription.request(n);
                }
            }
        }

        @Override
        public void cancel() {
            subscription.cancel();
        }
    }
}
