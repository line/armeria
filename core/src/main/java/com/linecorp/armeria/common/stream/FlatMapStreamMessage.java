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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
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
        completionFuture = new EventLoopCheckingFuture<>();
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
        private final Set<FlatMapSubscriber<T, U>> childSubscribers;
        private final Queue<U> buffer;
        private final CompletableFuture<Void> completionFuture;
        private final SubscriptionOption[] options;

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
                                     CompletableFuture<Void> completionFuture,
                                     SubscriptionOption... options) {
            requireNonNull(downstream, "downstream");
            requireNonNull(function, "function");
            requireNonNull(executor, "executor");
            requireNonNull(completionFuture, "completionFuture");

            this.downstream = downstream;
            this.function = function;
            this.executor = executor;
            this.maxConcurrency = maxConcurrency;
            this.completionFuture = completionFuture;
            this.options = options;

            childSubscribers = new HashSet<>();
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

            pendingSubscriptions++;
            final StreamMessage<U> newStreamMessage = function.apply(item);
            newStreamMessage.subscribe(new FlatMapSubscriber<>(this), executor, options);
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");

            if (canceled) {
                return;
            }
            canceled = true;

            cancelChildSubscribers();
            downstream.onError(cause);
        }

        @Override
        public void onComplete() {
            if (canceled) {
                return;
            }

            if (childSubscribers.isEmpty() && pendingSubscriptions == 0) {
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
                cancel();
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
            flush();

            final long toRequest = maxConcurrency - childSubscribers.size();
            if (toRequest > 0) {
                upstream.request(toRequest);
            }

            requestAllAvailable();
        }

        @Override
        public void cancel() {
            upstream.cancel();
            cancelChildSubscribers();
        }

        private void cancelChildSubscribers() {
            childSubscribers.forEach(FlatMapSubscriber::cancel);
        }

        void childSubscribed(FlatMapSubscriber<T, U> child) {
            pendingSubscriptions--;
            childSubscribers.add(child);

            requestAllAvailable();
        }

        private long getAvailableBufferSpace() {
            if (requestedByDownstream == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }

            final Optional<Long> requested = childSubscribers.stream().map(FlatMapSubscriber::getRequested)
                                                             .reduce(LongMath::saturatedAdd);

            return maxConcurrency - requested.orElse(0L) - buffer.size();
        }

        private void requestAllAvailable() {
            if (childSubscribers.isEmpty()) {
                return;
            }

            final long available = getAvailableBufferSpace();

            if (available == Long.MAX_VALUE) {
                childSubscribers.forEach(sub -> sub.request(Long.MAX_VALUE));
                return;
            }

            final List<FlatMapSubscriber<T, U>> toRequest = childSubscribers.stream()
                                                                            .filter(sub -> sub.getRequested() ==
                                                                                           0)
                                                                            .limit(available)
                                                                            .collect(toImmutableList());
            toRequest.forEach(sub -> sub.request(1));
        }

        private void flush() {
            while (requestedByDownstream > 0 && !buffer.isEmpty()) {
                final U value = buffer.remove();

                publishDownstream(value);
            }
        }

        void completeChild(FlatMapSubscriber<T, U> child) {
            childSubscribers.remove(child);

            if (childSubscribers.isEmpty() && pendingSubscriptions == 0 && completing) {
                flush();
                downstream.onComplete();
                completionFuture.complete(null);
            }
        }

        void onNextChild(U value) {
            requireNonNull(value, "value");
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
            parent.childSubscribed(this);
        }

        @Override
        public void onNext(U value) {
            if (requested != Long.MAX_VALUE) {
                requested--;
            }
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
            requested = LongMath.saturatedAdd(requested, n);
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
