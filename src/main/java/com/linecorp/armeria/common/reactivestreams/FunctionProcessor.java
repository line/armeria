/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.reactivestreams;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public final class FunctionProcessor<T, R> implements Processor<T, R> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<FunctionProcessor, Subscription> subscriptionUpdater =
            AtomicReferenceFieldUpdater.newUpdater(FunctionProcessor.class, Subscription.class, "subscription");

    @SuppressWarnings({ "rawtypes", "AtomicFieldUpdaterIssues" })
    private static final AtomicReferenceFieldUpdater<FunctionProcessor, Subscriber> subscriberUpdater =
            AtomicReferenceFieldUpdater.newUpdater(FunctionProcessor.class, Subscriber.class, "subscriber");

    private final Function<? super T, ? extends R> function;

    @SuppressWarnings("unused")
    private volatile Subscription subscription;
    @SuppressWarnings("unused")
    private volatile Subscriber<? super R> subscriber;

    public FunctionProcessor(Function<? super T, ? extends R> function) {
        this.function = requireNonNull(function, "function");
    }

    @Override
    public void subscribe(Subscriber<? super R> subscriber) {
        requireNonNull(subscriber, "subscriber");
        if (!subscriberUpdater.compareAndSet(this, null, subscriber)) {
            throw new IllegalStateException("subscribed by other subscriber already: " + this.subscriber);
        }

        final Subscription subscription = this.subscription;
        if (subscription != null) {
            subscriber.onSubscribe(subscription);
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
            throw new IllegalStateException("subscribed to other publisher already: " + this.subscription);
        }

        final Subscriber<? super R> subscriber = this.subscriber;
        if (subscriber != null) {
            subscriber.onSubscribe(subscription);
        }
    }

    @Override
    public void onNext(T obj) {
        subscriber().onNext(function.apply(obj));
    }

    @Override
    public void onError(Throwable cause) {
        subscriber().onError(cause);
    }

    @Override
    public void onComplete() {
        subscriber().onComplete();
    }

    private Subscriber<? super R> subscriber() {
        final Subscriber<? super R> subscriber = this.subscriber;
        if (subscriber == null) {
            throw new IllegalStateException();
        }

        return subscriber;
    }
}
