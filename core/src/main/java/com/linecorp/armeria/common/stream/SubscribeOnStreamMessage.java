/*
 * Copyright 2023 LINE Corporation
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

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.util.concurrent.EventExecutor;

final class SubscribeOnStreamMessage<T> implements StreamMessage<T> {

    private final StreamMessage<T> upstream;
    private final EventExecutor upstreamExecutor;

    SubscribeOnStreamMessage(StreamMessage<T> upstream, EventExecutor upstreamExecutor) {
        this.upstream = upstream;
        this.upstreamExecutor = upstreamExecutor;
    }

    @Override
    public boolean isOpen() {
        return upstream.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return upstream.isEmpty();
    }

    @Override
    public long demand() {
        return upstream.demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return upstream.whenComplete();
    }

    @Override
    public EventExecutor defaultSubscriberExecutor() {
        return upstreamExecutor;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor downstreamExecutor,
                          SubscriptionOption... options) {
        final Subscriber<? super T> subscriber0;
        if (upstreamExecutor == downstreamExecutor) {
            subscriber0 = subscriber;
        } else {
            subscriber0 = new SchedulingSubscriber<>(downstreamExecutor, subscriber);
        }
        if (upstreamExecutor.inEventLoop()) {
            upstream.subscribe(subscriber0, downstreamExecutor, options);
        } else {
            upstreamExecutor.execute(() -> upstream.subscribe(subscriber0, upstreamExecutor, options));
        }
    }

    @Override
    public void abort() {
        upstream.abort();
    }

    @Override
    public void abort(Throwable cause) {
        upstream.abort(cause);
    }

    static class SchedulingSubscriber<T> implements Subscriber<T> {

        private final Subscriber<? super T> downstream;
        private final EventExecutor downstreamExecutor;

        SchedulingSubscriber(EventExecutor downstreamExecutor, Subscriber<? super T> downstream) {
            this.downstream = downstream;
            this.downstreamExecutor = downstreamExecutor;
        }

        @Override
        public void onSubscribe(Subscription s) {
            downstreamExecutor.execute(() -> downstream.onSubscribe(s));
        }

        @Override
        public void onNext(T t) {
            downstreamExecutor.execute(() -> downstream.onNext(t));
        }

        @Override
        public void onError(Throwable t) {
            downstreamExecutor.execute(() -> downstream.onError(t));
        }

        @Override
        public void onComplete() {
            downstreamExecutor.execute(downstream::onComplete);
        }
    }
}
