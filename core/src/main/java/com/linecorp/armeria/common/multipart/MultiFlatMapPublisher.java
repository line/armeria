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

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Maps the upstream values into {@link Publisher}s,
 * subscribes to some of them and funnels their events into a single sequence.
 * @param <T> the upstream element type
 * @param <R> the element type of the resulting and inner publishers
 */
final class MultiFlatMapPublisher<T, R> implements Multi<R> {

    // Forked from https://github.com/oracle/helidon/blob/c369146ffe8a3cb0c6b2891e2aff970a3b9b3ad6/common
    // /reactive/src/main/java/io/helidon/common/reactive/MultiFlatMapPublisher.java

    private final Multi<T> source;
    private final Function<? super T, ? extends Publisher<? extends R>> mapper;

    private final long maxConcurrency;
    private final long prefetch;
    private final boolean delayErrors;

    MultiFlatMapPublisher(Multi<T> source, Function<? super T, ? extends Publisher<? extends R>> mapper,
                          long maxConcurrency, long prefetch, boolean delayErrors) {
        this.source = requireNonNull(source, "source");
        this.mapper = requireNonNull(mapper, "mapper");
        this.maxConcurrency = maxConcurrency;
        this.prefetch = prefetch;
        this.delayErrors = delayErrors;
    }

    @Override
    public void subscribe(Subscriber<? super R> subscriber) {
        requireNonNull(subscriber, "subscriber");
        source.subscribe(new FlatMapSubscriber<>(subscriber, mapper, maxConcurrency,
                                                 prefetch, delayErrors));
    }

    private static final class FlatMapSubscriber<T, R> extends AtomicInteger
            implements Subscriber<T>, Subscription {

        private static final long serialVersionUID = 3110403754615811849L;

        private final Subscriber<? super R> downstream;
        private final Function<? super T, ? extends Publisher<? extends R>> mapper;

        private final long maxConcurrency;
        private final long prefetch;
        private final boolean delayErrors;

        private final AtomicReference<Throwable> errors;
        private final ConcurrentMap<InnerSubscriber<R>, Object> subscribers;
        private final AtomicReference<Queue<InnerSubscriber<R>>> queue;
        private final AtomicLong requested;

        private volatile boolean cancelled;
        private volatile boolean upstreamDone;

        private long emitted;

        @Nullable
        private Subscription upstream;

        FlatMapSubscriber(Subscriber<? super R> downstream,
                          Function<? super T, ? extends Publisher<? extends R>> mapper,
                          long maxConcurrency, long prefetch, boolean delayErrors) {
            this.downstream = downstream;
            this.mapper = mapper;
            this.maxConcurrency = maxConcurrency;
            this.prefetch = prefetch;
            this.delayErrors = delayErrors;
            errors = new AtomicReference<>();
            subscribers = new ConcurrentHashMap<>();
            queue = new AtomicReference<>();
            requested = new AtomicLong();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (upstream != null) {
                subscription.cancel();
                throw new IllegalStateException("Subscription already set");
            }
            upstream = subscription;
            downstream.onSubscribe(this);
            subscription.request(maxConcurrency);
        }

        @Override
        public void onNext(T item) {
            if (!upstreamDone) {
                final Publisher<? extends R> innerSource;

                try {
                    innerSource = requireNonNull(mapper.apply(item),
                                                 "The mapper returned a null Publisher");
                } catch (Throwable ex) {
                    upstream.cancel();
                    onError(ex);
                    return;
                }

                final InnerSubscriber<R> innerSubscriber = new InnerSubscriber<>(this, prefetch);
                subscribers.put(innerSubscriber, innerSubscriber);
                if (cancelled) {
                    subscribers.remove(innerSubscriber);
                    return;
                }

                innerSource.subscribe(innerSubscriber);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!upstreamDone) {
                doError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!upstreamDone) {
                upstreamDone = true;
                drain();
            }
        }

        private void doError(Throwable throwable) {
            if (delayErrors) {
                addError(throwable);
            } else {
                errors.compareAndSet(null, throwable);
                cancelInners();
            }
            upstreamDone = true;

            drain();
        }

        @Override
        public void request(long n) {
            if (n <= 0L) {
                doError(new IllegalArgumentException(
                        "Rule §3.9 violated: non-positive request amount is forbidden"));
            } else {
                SubscriptionHelper.addRequest(requested, n);
                drain();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            upstream.cancel();
            cancelInners();
        }

        private void cancelInners() {
            for (InnerSubscriber<R> inner : subscribers.keySet()) {
                inner.cancel();
            }
            subscribers.clear();
        }

        void innerNext(R item, InnerSubscriber<R> sender) {
            // fast enter into the serializer
            if (get() == 0 && compareAndSet(0, 1)) {
                final long r = requested.get();
                final long e = emitted;
                // is the downstream ready to receive an item
                if (r != e) {
                    final Queue<InnerSubscriber<R>> q = queue.get();
                    // are there prior items queued up?
                    if (q == null || q.isEmpty()) {
                        emitted = e + 1;
                        downstream.onNext(item);
                        sender.produced(1L);
                    } else {
                        // yes, go on a full drain loop
                        sender.enqueue(item);
                        q.offer(sender);
                        drainLoop();
                        return;
                    }
                } else {
                    // downstream is not ready, queue up the work
                    sender.enqueue(item);
                    getOrCreateQueue().offer(sender);
                }
                // is there more work to be done?
                if (decrementAndGet() == 0) {
                    return;
                }
            } else {
                sender.enqueue(item);
                // queue up the item
                getOrCreateQueue().offer(sender);
                // can we enter the drain loop?
                if (getAndIncrement() != 0) {
                    return;
                }
            }
            drainLoop();
        }

        void innerError(Throwable ex, InnerSubscriber<R> sender) {
            if (delayErrors) {
                addError(ex);
                sender.setDone();
            } else {
                errors.compareAndSet(null, ex);
                upstream.cancel();
                cancelInners();
                sender.setDone();
                upstreamDone = true;
            }
            getOrCreateQueue().offer(sender);
            drain();
        }

        void innerComplete(InnerSubscriber<R> sender) {
            sender.setDone();
            if (get() == 0 && compareAndSet(0, 1)) {

                final Queue<R> innerQueue = sender.getQueue();
                if (innerQueue == null || innerQueue.isEmpty()) {
                    subscribers.remove(sender);

                    final boolean done = upstreamDone;
                    final Queue<InnerSubscriber<R>> mainQueue = queue.get();
                    final boolean mainQueueEmpty = mainQueue == null || mainQueue.isEmpty();
                    final boolean noMoreSubscribers = subscribers.isEmpty();

                    if (done && mainQueueEmpty && noMoreSubscribers) {
                        final Throwable ex = errors.get();
                        if (ex == null) {
                            downstream.onComplete();
                        } else {
                            downstream.onError(ex);
                        }
                        cancelled = true;
                    } else {
                        if (!done) {
                            upstream.request(1L);
                        }
                    }
                } else {
                    getOrCreateQueue().offer(sender);
                }
                if (decrementAndGet() == 0) {
                    return;
                }
            } else {
                getOrCreateQueue().offer(sender);
                if (getAndIncrement() != 0) {
                    return;
                }
            }
            drainLoop();
        }

        private Queue<InnerSubscriber<R>> getOrCreateQueue() {
            Queue<InnerSubscriber<R>> q = queue.get();
            if (q == null) {
                q = new ConcurrentLinkedQueue<>();
                if (!queue.compareAndSet(null, q)) {
                    q = queue.get();
                }
            }
            return q;
        }

        private void addError(Throwable throwable) {
            for (;;) {
                final Throwable ex = errors.get();
                if (ex == null) {
                    if (errors.compareAndSet(null, throwable)) {
                        return;
                    }
                } else if (ex instanceof FlatMapAggregateException) {
                    ex.addSuppressed(throwable);
                    return;
                } else {
                    final Throwable newEx = new FlatMapAggregateException();
                    newEx.addSuppressed(ex);
                    newEx.addSuppressed(throwable);
                    if (errors.compareAndSet(ex, newEx)) {
                        return;
                    }
                }
            }
        }

        private void drain() {
            if (getAndIncrement() == 0) {
                drainLoop();
            }
        }

        private void drainLoop() {

            int missed = 1;

            long r = requested.get();
            long e = emitted;

            final Subscriber<? super R> downstream = this.downstream;
            final AtomicReference<Queue<InnerSubscriber<R>>> queue = this.queue;
            final ConcurrentMap<?, ?> subscribers = this.subscribers;

            for (;;) {

                if (cancelled) {
                    queue.lazySet(null);
                    subscribers.clear();
                } else {
                    if (!delayErrors) {
                        final Throwable ex = errors.get();
                        if (ex != null) {
                            cancelled = true;
                            downstream.onError(ex);
                            continue;
                        }
                    }

                    final boolean done = upstreamDone;
                    final boolean noActiveInnerSubscribers = subscribers.isEmpty();
                    final Queue<InnerSubscriber<R>> q = queue.get();
                    final boolean noQueuedItems = q == null || q.isEmpty();

                    if (done && noActiveInnerSubscribers && noQueuedItems) {
                        cancelled = true;
                        final Throwable ex = errors.get();
                        if (ex != null) {
                            downstream.onError(ex);
                        } else {
                            downstream.onComplete();
                        }
                        continue;
                    }

                    if (!noQueuedItems) {
                        final InnerSubscriber<R> inner = q.peek();

                        final boolean innerDone = inner.isDone();
                        final Queue<R> innerQueue = inner.getQueue();
                        final boolean innerEmpty = innerQueue == null || innerQueue.isEmpty();

                        if (innerDone && innerEmpty) {
                            subscribers.remove(inner);
                            q.poll();
                            upstream.request(1L);
                            continue;
                        }

                        if (!innerEmpty) {
                            if (r != e) {
                                q.poll();
                                final R v = innerQueue.poll();
                                e++;
                                downstream.onNext(v);
                                inner.produced(1L);
                                continue;
                            }
                        }
                    }
                }

                emitted = e;
                missed = addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
                r = requested.get();
            }
        }

        /**
         * Instances of this class will be subscribed to the mapped inner
         * Publishers and calls back to the enclosing parent class.
         * @param <R> the element type of the inner sequence
         */
        static final class InnerSubscriber<R> extends AtomicReference<Subscription>
                implements Subscriber<R>, Subscription {

            private static final long serialVersionUID = 8561434642409382652L;

            private final FlatMapSubscriber<?, R> parent;
            private final long prefetch;
            private final long limit;

            private long produced;
            private volatile boolean done;

            @Nullable
            private volatile Queue<R> queue;

            InnerSubscriber(FlatMapSubscriber<?, R> parent, long prefetch) {
                this.parent = parent;
                this.prefetch = prefetch;
                limit = prefetch - (prefetch >> 2);
            }

            @Override
            public void onSubscribe(Subscription subscription) {
                if (SubscriptionHelper.setOnce(this, subscription)) {
                    subscription.request(prefetch);
                }
            }

            @Override
            public void onNext(R item) {
                parent.innerNext(item, this);
            }

            @Override
            public void onError(Throwable throwable) {
                lazySet(this);
                parent.innerError(throwable, this);
            }

            @Override
            public void onComplete() {
                lazySet(this);
                parent.innerComplete(this);
            }

            @Override
            public void request(long n) {
                // deliberately empty
            }

            private void produced(long n) {
                final long p = produced + n;
                if (p >= limit) {
                    produced = 0L;
                    get().request(p);
                } else {
                    produced = p;
                }
            }

            @Override
            public void cancel() {
                final Subscription s = getAndSet(this);
                if (s != null && s != this) {
                    s.cancel();
                }
            }

            @Nullable
            private Queue<R> getQueue() {
                return queue;
            }

            private void enqueue(R item) {
                Queue<R> q = queue;
                if (q == null) {
                    q = new ConcurrentLinkedQueue<>();
                    queue = q;
                }
                q.offer(item);
            }

            private void setDone() {
                done = true;
            }

            private boolean isDone() {
                return done;
            }

            @Override
            public String toString() {
                final boolean d = done;
                final Queue<R> q = queue;
                return "InnerSubscriber{" +
                       "done=" + d +
                       ", queue=" + (q != null ? q.size() : "null") +
                       '}';
            }
        }
    }

    /**
     * Used for aggregating multiple exceptions via the {@link #addSuppressed(Throwable)}
     * method.
     */
    private static final class FlatMapAggregateException extends RuntimeException {
        private static final long serialVersionUID = 8779904532464658198L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this; // No stacktrace of its own as it aggregates other exceptions
        }
    }
}
