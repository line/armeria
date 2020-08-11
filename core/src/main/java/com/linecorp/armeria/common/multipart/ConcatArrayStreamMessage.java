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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * Relay items in order from subsequent {@link StreamMessage}s as a single {@link StreamMessage} source.
 */
final class ConcatArrayStreamMessage<T> extends SimpleStreamMessage<T> {

    // Forked from https://github.com/oracle/helidon/blob/28cb3e8a34bda691c035d21f90b6278c6a42007c/common/reactive/src/main/java/io/helidon/common/reactive/MultiConcatArray.java

    private final StreamMessage<? extends T>[] sources;

    ConcatArrayStreamMessage(StreamMessage<? extends T>[] sources) {
        this.sources = sources;
    }

    @Override
    public boolean isOpen() {
        for (StreamMessage<? extends T> source : sources) {
            if (source.isOpen()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isEmpty() {
        for (StreamMessage<? extends T> source : sources) {
            if (!source.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return sources[sources.length - 1].whenComplete();
    }

    @Override
    void subscribe(Subscriber<? super T> subscriber, boolean notifyCancellation) {
        requireNonNull(subscriber, "subscriber");
        final ConcatArraySubscriber<T> parent =
                new ConcatArraySubscriber<>(subscriber, sources, notifyCancellation);
        subscriber.onSubscribe(parent);
        parent.nextSource();
    }

    @Override
    public void abort() {
        for (StreamMessage<? extends T> source : sources) {
            source.abort();
        }
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        for (StreamMessage<? extends T> source : sources) {
            source.abort(cause);
        }
    }

    private static final class ConcatArraySubscriber<T> extends SubscriptionArbiter implements Subscriber<T> {
        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<ConcatArraySubscriber> wipUpdater =
                AtomicIntegerFieldUpdater.newUpdater(ConcatArraySubscriber.class, "wip");

        private static final long serialVersionUID = -9184116713095894096L;

        private final Subscriber<? super T> downstream;
        private final StreamMessage<? extends T>[] sources;
        private final boolean notifyCancellation;

        private int index;
        private long produced;
        private volatile int wip;
        private boolean cancelRequested;

        ConcatArraySubscriber(Subscriber<? super T> downstream, StreamMessage<? extends T>[] sources,
                              boolean notifyCancellation) {
            this.downstream = downstream;
            this.sources = sources;
            this.notifyCancellation = notifyCancellation;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (cancelRequested) {
                subscription.cancel();
            } else {
                setSubscription(subscription);
            }
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
            if (wipUpdater.getAndIncrement(this) == 0) {
                do {
                    if (index == sources.length) {
                        downstream.onComplete();
                    } else {
                        sources[index++].subscribe(this);
                    }
                } while (wipUpdater.decrementAndGet(this) != 0);
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

        @Override
        public void cancel() {
            cancelRequested = true;
            super.cancel();
            if (notifyCancellation) {
                downstream.onError(CancelledSubscriptionException.get());
            }
        }
    }
}
