/*
 * Copyright 2022 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.stream.StreamMessageUtil;

import io.netty.util.concurrent.EventExecutor;

final class FlatMapStreamMessage<T, U> implements StreamMessage<U> {
    private final StreamMessage<T> source;
    private final Function<T, StreamMessage<U>> function;
    private final int maxConcurrency;

    private final CompletableFuture<Void> completionFuture;
    private FlatMapAggregatingSubscriber<T, U> innerSubscriber;

    @SuppressWarnings("unchecked")
    FlatMapStreamMessage(StreamMessage<? extends T> source,
                         Function<? super T, ? extends StreamMessage<? extends U>> function,
                         int maxConcurrency) {
        requireNonNull(source, "source");
        requireNonNull(function, "function");

        this.source = (StreamMessage<T>) source;
        this.function = (Function<T, StreamMessage<U>>) function;
        this.maxConcurrency = maxConcurrency;
        completionFuture = new CompletableFuture<>();
    }

    @Override
    public boolean isOpen() {
        return source.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return source.isEmpty();
    }

    @Override
    public long demand() {
        return innerSubscriber.requestedByDownstream;
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return completionFuture;
    }

    @Override
    public void subscribe(Subscriber<? super U> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        innerSubscriber = new FlatMapAggregatingSubscriber<>(subscriber, function, executor, maxConcurrency,
                                                             completionFuture);

        source.subscribe(innerSubscriber, executor, options);
    }

    @Override
    public void abort() {
        source.abort();
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        source.abort(cause);
    }

    private static final class FlatMapAggregatingSubscriber<T, U> implements Subscriber<T>, Subscription {
        private final int maxConcurrency;

        private final Subscriber<? super U> downstream;
        private final Function<T, StreamMessage<U>> function;
        private final EventExecutor executor;
        private final Set<FlatMapSubscriber<T, U>> sourceSubscriptions;
        private final Queue<U> buffer;
        private final CompletableFuture<Void> completionFuture;

        @Nullable
        private volatile Subscription upstream;
        private volatile boolean canceled;

        private long requestedByDownstream;
        private int pendingSubscriptions;
        private boolean completing;

        FlatMapAggregatingSubscriber(Subscriber<? super U> downstream,
                                     Function<T, StreamMessage<U>> function,
                                     EventExecutor executor,
                                     int maxConcurrency,
                                     CompletableFuture<Void> completionFuture) {
            requireNonNull(downstream, "downstream");
            requireNonNull(function, "function");
            requireNonNull(executor, "executor");
            requireNonNull(completionFuture, "completionFuture");

            this.downstream = downstream;
            this.function = function;
            this.executor = executor;
            this.maxConcurrency = maxConcurrency;
            this.completionFuture = completionFuture;

            sourceSubscriptions = new HashSet<>();
            buffer = new ArrayDeque<>();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");

            upstream = subscription;
            downstream.onSubscribe(this);
        }

        @Override
        public void onNext(T item) {
            requireNonNull(item, "item");

            if (canceled) {
                StreamMessageUtil.closeOrAbort(item);
                return;
            }

            final StreamMessage<U> newStreamMessage = function.apply(item);
            newStreamMessage.subscribe(new FlatMapSubscriber<>(this));
            pendingSubscriptions++;
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");

            if (canceled) {
                return;
            }
            canceled = true;

            cancelSourceSubscriptions();
            downstream.onError(cause);
        }

        @Override
        public void onComplete() {
            if (canceled) {
                return;
            }

            if (sourceSubscriptions.isEmpty() && pendingSubscriptions == 0) {
                downstream.onComplete();
                completionFuture.complete(null);
            } else {
                completing = true;
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                onError(new IllegalArgumentException(
                        "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
                upstream.cancel();
                return;
            }

            if (canceled) {
                return;
            }

            if (executor.inEventLoop()) {
                handleRequest(n);
            } else {
                executor.execute(() -> handleRequest(n));
            }
        }

        private void handleRequest(long n) {
            requestedByDownstream = LongMath.saturatedAdd(requestedByDownstream, n);
            upstream.request(maxConcurrency - sourceSubscriptions.size());

            flush();
            requestAllAvailable();
        }

        @Override
        public void cancel() {
            upstream.cancel();
            cancelSourceSubscriptions();
        }

        private void cancelSourceSubscriptions() {
            sourceSubscriptions.forEach(FlatMapSubscriber::cancel);
        }

        void subscribeChild(FlatMapSubscriber<T, U> child) {
            if (executor.inEventLoop()) {
                handleSubscribeChild(child);
            } else {
                executor.execute(() -> handleSubscribeChild(child));
            }
        }

        private void handleSubscribeChild(FlatMapSubscriber<T, U> child) {
            pendingSubscriptions--;
            sourceSubscriptions.add(child);

            requestAllAvailable();
        }

        private long getAvailableBufferSpace() {
            if (requestedByDownstream == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }

            final Optional<Long> requested = sourceSubscriptions.stream().map(FlatMapSubscriber::getRequested)
                                                                .reduce(LongMath::saturatedAdd);

            return maxConcurrency - requested.orElse(0L) - buffer.size();
        }

        private void requestAllAvailable() {
            if (sourceSubscriptions.isEmpty()) {
                return;
            }

            final long available = getAvailableBufferSpace();
            sourceSubscriptions.stream()
                               .filter(sub -> sub.getRequested() == 0)
                               .limit(available).forEach(sub -> sub.request(1));
        }

        private void flush() {
            while (requestedByDownstream > 0 && !buffer.isEmpty()) {
                final U value = buffer.remove();

                publishDownstream(value);
            }
        }

        void completeChild(FlatMapSubscriber<T, U> child) {
            if (executor.inEventLoop()) {
                handleCompleteChild(child);
            } else {
                executor.execute(() -> handleCompleteChild(child));
            }
        }

        private void handleCompleteChild(FlatMapSubscriber<T, U> child) {
            sourceSubscriptions.remove(child);

            if (sourceSubscriptions.isEmpty() && pendingSubscriptions == 0 && completing) {
                flush();
                downstream.onComplete();
            }
        }

        void onNextChild(U value) {
            requireNonNull(value, "value");
            if (executor.inEventLoop()) {
                handleOnNextChild(value);
            } else {
                executor.execute(() -> handleOnNextChild(value));
            }
        }

        private void handleOnNextChild(U value) {
            if (requestedByDownstream > 0) {
                publishDownstream(value);
            } else {
                buffer.add(value);
            }

            requestAllAvailable();
        }

        private void publishDownstream(U item) {
            if (canceled) {
                StreamMessageUtil.closeOrAbort(item);
                return;
            }

            downstream.onNext(item);

            if (requestedByDownstream != Long.MAX_VALUE) {
                requestedByDownstream--;
            }
        }
    }

    private static final class FlatMapSubscriber<T, U> implements Subscriber<U> {
        private final FlatMapAggregatingSubscriber<T, U> parent;

        private long requested;

        @Nullable
        private Subscription subscription;

        FlatMapSubscriber(FlatMapAggregatingSubscriber<T, U> parent) {
            requireNonNull(parent, "parent");

            this.parent = parent;
            requested = 0;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");

            this.subscription = subscription;
            parent.subscribeChild(this);
        }

        @Override
        public void onNext(U value) {
            requested--;
            parent.onNextChild(value);
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");

            subscription.cancel();

            parent.onError(cause);
        }

        @Override
        public void onComplete() {
            parent.completeChild(this);
        }

        public void request(long n) {
            requested += n;
            subscription.request(n);
        }

        public void cancel() {
            subscription.cancel();
        }

        public long getRequested() {
            return requested;
        }
    }
}
