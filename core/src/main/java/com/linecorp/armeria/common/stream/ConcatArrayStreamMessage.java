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

package com.linecorp.armeria.common.stream;

import static com.linecorp.armeria.common.stream.StreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.util.concurrent.EventExecutor;

/**
 * Relay items in order from subsequent {@link StreamMessage}s as a single {@link StreamMessage} source.
 */
final class ConcatArrayStreamMessage<T> implements StreamMessage<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<ConcatArrayStreamMessage> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ConcatArrayStreamMessage.class, "subscribed");

    private final List<StreamMessage<? extends T>> sources;

    @Nullable
    private ConcatArraySubscriber<T> parent;

    private volatile int subscribed;

    ConcatArrayStreamMessage(List<StreamMessage<? extends T>> sources) {
        assert !sources.isEmpty();
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
    public long demand() {
        if (parent == null) {
            return 0;
        }
        return sources.get(parent.index).demand();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return sources.get(sources.size() - 1).whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, EMPTY_OPTIONS);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        if (subscribedUpdater.compareAndSet(this, 0, 1)) {
            parent = new ConcatArraySubscriber<>(subscriber, sources, executor, options);
            subscriber.onSubscribe(parent);
            if (executor.inEventLoop()) {
                parent.nextSource();
            } else {
                executor.execute(() -> parent.nextSource());
            }
        } else {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(new IllegalStateException("subscribed by other subscriber already"));
        }
    }

    @Override
    public void abort() {
        abort(AbortedStreamException.get());
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
        private static final AtomicIntegerFieldUpdater<ConcatArraySubscriber> cancelledUpdater =
                AtomicIntegerFieldUpdater.newUpdater(ConcatArraySubscriber.class, "cancelled");

        private Subscriber<? super T> downstream;
        private final List<StreamMessage<? extends T>> sources;
        private final EventExecutor executor;
        private final SubscriptionOption[] options;

        private long produced;

        private volatile int index;
        private volatile int cancelled;

        ConcatArraySubscriber(Subscriber<? super T> downstream, List<StreamMessage<? extends T>> sources,
                              EventExecutor executor, SubscriptionOption... options) {
            this.downstream = downstream;
            this.sources = sources;
            this.executor = executor;
            this.options = options;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (cancelled()) {
                subscription.cancel();
            } else {
                setUpstreamSubscription(subscription);
            }
        }

        @Override
        public void onNext(T item) {
            requireNonNull(item, "item");
            produced++;
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            requireNonNull(throwable, "throwable");
            abortUnsubscribedSources(throwable);
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
            if (!cancelled()) {
                final int index = this.index;
                if (index == sources.size()) {
                    downstream.onComplete();
                } else {
                    this.index++;
                    sources.get(index).subscribe(this, executor, options);
                }
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
            if (cancelledUpdater.compareAndSet(this, 0, 1)) {
                super.cancel();
                abortUnsubscribedSources(CancelledSubscriptionException.get());

                if (containsNotifyCancellation(options)) {
                    downstream.onError(CancelledSubscriptionException.get());
                }
                downstream = NoopSubscriber.get();
            }
        }

        private boolean cancelled() {
            return cancelled != 0;
        }

        private void abortUnsubscribedSources(Throwable cause) {
            final int index = this.index;
            for (int i = index; i < sources.size(); i++) {
                sources.get(i).abort(cause);
            }
        }
    }
}
