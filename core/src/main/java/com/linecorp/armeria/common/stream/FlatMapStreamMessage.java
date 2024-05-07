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
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsNotifyCancellation;
import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableSet;
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
    @Nullable
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
        return !completionFuture.isDone();
    }

    @Override
    public boolean isEmpty() {
        return !isOpen() && innerSubscriber != null && !innerSubscriber.publishedAny;
    }

    @Override
    public long demand() {
        if (innerSubscriber == null) {
            return 0;
        }
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

        private final boolean notifyCancellation;

        @Nullable
        private volatile Subscription upstream;

        private long requestedByDownstream;
        private boolean closed;
        private boolean completing;
        private boolean initialized;
        private boolean publishedAny;

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

            notifyCancellation = containsNotifyCancellation(options);

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

            if (closed) {
                StreamMessageUtil.closeOrAbort(item);
                return;
            }

            final StreamMessage<U> newStreamMessage = function.apply(item);
            final FlatMapSubscriber<T, U> childSubscriber = new FlatMapSubscriber<>(this);
            childSubscribers.add(childSubscriber);
            newStreamMessage.subscribe(childSubscriber, executor, options);
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");

            if (closed) {
                return;
            }
            closed = true;

            cancelChildSubscribersAndBuffer(cause);
            downstream.onError(cause);
            completionFuture.completeExceptionally(cause);
        }

        @Override
        public void onComplete() {
            if (closed) {
                return;
            }

            completing = true;
            if (canComplete()) {
                complete();
            }
        }

        private void complete() {
            downstream.onComplete();
            completionFuture.complete(null);
            closed = true;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                onError(new IllegalArgumentException(
                        "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
                cancel();
                return;
            }

            if (executor.inEventLoop()) {
                handleRequest(n);
            } else {
                executor.execute(() -> handleRequest(n));
            }
        }

        private void handleRequest(long n) {
            if (closed) {
                return;
            }

            requestedByDownstream = LongMath.saturatedAdd(requestedByDownstream, n);
            flush();

            if (!initialized) {
                initialized = true;
                final Subscription upstream = this.upstream;
                assert upstream != null;
                upstream.request(maxConcurrency);
            }

            requestAllAvailable();
        }

        @Override
        public void cancel() {
            if (executor.inEventLoop()) {
                cancel0();
            } else {
                executor.execute(this::cancel0);
            }
        }

        private void cancel0() {
            if (closed) {
                return;
            }

            closed = true;
            final Subscription upstream = this.upstream;
            assert upstream != null;
            upstream.cancel();
            final CancelledSubscriptionException cause = CancelledSubscriptionException.get();
            cancelChildSubscribersAndBuffer(cause);
            completionFuture.completeExceptionally(cause);

            if (notifyCancellation) {
                downstream.onError(cause);
            }
        }

        private void cancelChildSubscribersAndBuffer(Throwable cause) {
            for (U item : buffer) {
                StreamMessageUtil.closeOrAbort(item, cause);
            }
            buffer.clear();
            // use a copy of the subscribers to avoid a ConcurrentModificationException from
            // removing a child subscriber at #completeChild
            ImmutableSet.copyOf(childSubscribers).forEach(FlatMapSubscriber::cancel);
        }

        private long getAvailableBufferSpace() {
            if (requestedByDownstream == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }

            final long requested = childSubscribers.stream().map(FlatMapSubscriber::getRequested)
                                                   .reduce(0L, LongMath::saturatedAdd);
            return maxConcurrency - requested - buffer.size();
        }

        private void requestAllAvailable() {
            if (childSubscribers.isEmpty()) {
                return;
            }

            final long available = getAvailableBufferSpace();

            if (available == Long.MAX_VALUE) {
                // use a copy of the subscribers to avoid a ConcurrentModificationException from
                // removing a child subscriber at #completeChild
                childSubscribers.stream()
                                .filter(FlatMapSubscriber::subscribed)
                                .collect(toImmutableList())
                                .forEach(sub -> sub.request(Long.MAX_VALUE));
                return;
            }

            // use a copy of the subscribers to avoid a ConcurrentModificationException from
            // removing a child subscriber at #completeChild
            final List<FlatMapSubscriber<T, U>> toRequest =
                    childSubscribers.stream()
                                    .filter(FlatMapSubscriber::subscribed)
                                    .filter(sub -> sub.getRequested() == 0)
                                    .limit(available)
                                    .collect(toImmutableList());
            toRequest.forEach(sub -> sub.request(1));
        }

        private void flush() {
            while (requestedByDownstream > 0 && !buffer.isEmpty()) {
                final U value = buffer.remove();

                publishDownstream(value);
            }

            if (canComplete()) {
                complete();
            }
        }

        void completeChild(FlatMapSubscriber<T, U> child) {
            childSubscribers.remove(child);

            if (canComplete()) {
                complete();
                return;
            }

            if (!closed && !completing) {
                final Subscription upstream = this.upstream;
                assert upstream != null;
                upstream.request(1);
            }
        }

        private boolean canComplete() {
            return completing && childSubscribers.isEmpty() && buffer.isEmpty();
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
            if (closed) {
                StreamMessageUtil.closeOrAbort(item, ClosedStreamException.get());
                return;
            }

            publishedAny = true;

            if (requestedByDownstream != Long.MAX_VALUE) {
                requestedByDownstream--;
            }
            downstream.onNext(item);
        }
    }

    private static final class FlatMapSubscriber<T, U> implements Subscriber<U> {
        private final FlatMapAggregatingSubscriber<T, U> parent;

        private long requested;

        private boolean canceled;

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
            if (canceled) {
                subscription.cancel();
                return;
            }

            this.subscription = subscription;
            parent.requestAllAvailable();
        }

        @Override
        public void onNext(U value) {
            if (requested != Long.MAX_VALUE) {
                requested--;
            }

            if (canceled) {
                StreamMessageUtil.closeOrAbort(value);
                return;
            }

            parent.onNextChild(value);
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");

            if (canceled) {
                return;
            }
            canceled = true;

            parent.onError(cause);
        }

        @Override
        public void onComplete() {
            parent.completeChild(this);
        }

        public void request(long n) {
            assert subscription != null;
            requested = LongMath.saturatedAdd(requested, n);
            subscription.request(n);
        }

        public void cancel() {
            if (canceled) {
                return;
            }
            canceled = true;
            if (subscription != null) {
                subscription.cancel();
            }
        }

        public long getRequested() {
            return requested;
        }

        boolean subscribed() {
            return subscription != null;
        }
    }
}
