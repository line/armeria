/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

/**
 * Allows subscribing to a {@link StreamMessage} multiple times by duplicating the stream.
 * <p>
 * Only one subscriber can subscribe other stream messages such as {@link DefaultStreamMessage},
 * {@link DeferredStreamMessage}, etc.
 * This factory is wrapping one of those {@link StreamMessage}s and spawns duplicated stream messages
 * which are created using {@link AbstractStreamMessageDuplicator#duplicateStream()} and subscribed
 * by subscribers one by one.
 * </p><p>
 * The published elements can be shared across {@link Subscriber}s, so do not manipulate the
 * data unless you copy them. Only one case does not share the elements which is when you
 * {@link StreamMessage#subscribe(Subscriber, boolean)} with the {@code withPooledObjects}
 * as {@code false} while the elements is one of two pooled object classes which are {@link ByteBufHolder}
 * and {@link ByteBuf}.
 * </p><p>
 * This factory has to be closed by {@link AbstractStreamMessageDuplicator#close()} when
 * you do not need the contents anymore, otherwise memory leak might happen.
 * </p>
 * @param <T> the type of elements
 * @param <U> the type of the publisher and duplicated stream messages
 */
public abstract class AbstractStreamMessageDuplicator<T, U extends StreamMessage<? extends T>>
        implements SafeCloseable {

    private final StreamMessageProcessor<T> processor;

    /**
     * Creates a new instance wrapping a {@code publisher} and publishing to multiple subscribers.
     * @param publisher the publisher who will publish data to subscribers
     */
    protected AbstractStreamMessageDuplicator(U publisher) {
        requireNonNull(publisher, "publisher");
        processor = new StreamMessageProcessor<>(publisher);
    }

    /**
     * Creates a new {@link U} instance that publishes data from the {@code publisher} you create
     * this factory with.
     */
    public U duplicateStream() {
        if (!processor.isOpen()) {
            throw new IllegalStateException("This factory has been closed already.");
        }
        return doDuplicateStream(new ChildStreamMessage<>(processor));
    }

    /**
     * Creates a new {@link U} instance that wraps {@link ChildStreamMessage} and forwards its method
     * invocations to it.
     * @param delegate {@link ChildStreamMessage}
     */
    protected abstract U doDuplicateStream(StreamMessage<T> delegate);

    /**
     * Closes this factory and stream messages who are invoked by
     * {@link AbstractStreamMessageDuplicator#duplicateStream()}.
     * Also, clean up the data published from {@code publisher}.
     */
    @Override
    public void close() {
        processor.close();
    }

    @VisibleForTesting
    static class StreamMessageProcessor<T> implements Subscriber<T> {

        private enum State {
            /**
             * The initial state. Will enter {@link #CLOSED}.
             */
            OPEN,
            /**
              * {@link AbstractStreamMessageDuplicator#close()} has been called.
             */
            CLOSED
        }

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<StreamMessageProcessor, State> stateUpdater =
                AtomicReferenceFieldUpdater.newUpdater(StreamMessageProcessor.class, State.class, "state");

        @SuppressWarnings("rawtypes")
        private static final AtomicLongFieldUpdater<StreamMessageProcessor> requestedDemandUpdater =
                AtomicLongFieldUpdater.newUpdater(StreamMessageProcessor.class, "requestedDemand");

        private final Set<DownstreamSubscription> downstreamSubscriptions =
                Collections.newSetFromMap(new ConcurrentHashMap<>());

        private final List<T> contentList = new ArrayList<>();

        @SuppressWarnings("unused")
        private volatile long requestedDemand;
        private volatile Subscription upstreamSubscription;
        private volatile boolean upstreamCompleted;
        private volatile int upstreamOffset;
        private volatile State state = State.OPEN;

        private Throwable upstreamCause;

        StreamMessageProcessor(StreamMessage<? extends T> upstream) {
            upstream.subscribe(this, true);
            notifyDownstreamWhenUpstreamCompleted(upstream);
        }

        private void notifyDownstreamWhenUpstreamCompleted(StreamMessage<? extends T> upstream) {
            upstream.closeFuture().whenComplete((unused, cause) -> {
                upstreamCompleted = true;
                if (cause != null) {
                    upstreamCause = cause;
                }
                notifyDownstreams();
            });
        }

        @Override
        public void onSubscribe(Subscription s) {
            upstreamSubscription = s;
            downstreamSubscriptions.forEach(downstream -> {
                if (downstream.setSubscribed()) {
                    if (downstream.executor() != null) {
                        downstream.executor().execute(() -> downstream.subscriber().onSubscribe(downstream));
                    } else {
                        downstream.subscriber().onSubscribe(downstream);
                    }
                }
            });
            if (state == State.CLOSED) {
                s.cancel();
            }
        }

        @Override
        public void onNext(T t) {
            if (upstreamOffset >= Integer.MAX_VALUE) {
                //TODO(minwoox) limit by the size of data not by the size of contentList.
                upstreamSubscription.cancel();
                throw new IllegalStateException("Published numbers of data is greater than Integer.MAX_VALUE");
            }
            contentList.add(t);
            upstreamOffset++;
            notifyDownstreams();
        }

        /**
         * Handled by {@link #notifyDownstreamWhenUpstreamCompleted(StreamMessage)} instead.
         */
        @Override
        public void onError(Throwable t) {}

        /**
         * Handled by {@link #notifyDownstreamWhenUpstreamCompleted(StreamMessage)} instead.
         */
        @Override
        public void onComplete() {}

        void subscribe(DownstreamSubscription subscription) {
            if (state == State.OPEN) {
                downstreamSubscriptions.add(subscription);
                if (state == State.CLOSED) {
                    downstreamSubscriptions.remove(subscription);
                    throw new IllegalStateException("This factory has been closed already.");
                }
            } else {
                throw new IllegalStateException("This factory has been closed already.");
            }

            final Subscriber<Object> subscriber = subscription.subscriber();
            if (upstreamSubscription != null && subscription.setSubscribed()) {
                final Executor executor = subscription.executor();
                if (executor != null) {
                    executor.execute(() -> subscriber.onSubscribe(subscription));
                } else {
                    subscriber.onSubscribe(subscription);
                }
            }
        }

        void requestDemand(long cumulativeDemand) {
            for (;;) {
                if (cumulativeDemand <= requestedDemand) {
                    return;
                }
                long currentRequested = requestedDemand;
                if (requestedDemandUpdater.compareAndSet(this, currentRequested, cumulativeDemand)) {
                    upstreamSubscription.request(cumulativeDemand - currentRequested);
                    return;
                }
            }
        }

        void notifyDownstreams() {
            if (downstreamSubscriptions.isEmpty()) {
                return;
            }
            downstreamSubscriptions.forEach(downstream -> {
                final Executor executor = downstream.executor();
                if (executor != null) {
                    executor.execute(() -> notifyDownstream(downstream));
                } else {
                    notifyDownstream(downstream);
                }
            });
        }

        void notifyDownstream(DownstreamSubscription downstream) {
            for (;;) {
                int offsetOfSubscriber = downstream.offset;
                int upstreamOffset = this.upstreamOffset;
                if (upstreamCompleted && offsetOfSubscriber == upstreamOffset) {
                    if (DownstreamSubscription.notifyingUpdater.compareAndSet(downstream, 0, 1)) {
                        completeDownstream(downstream, upstreamCause);
                        // Don't have to bring notifying back to 0 because it's completed.
                    }
                    return;
                }

                if (downstream.cancelled) {
                    if (DownstreamSubscription.notifyingUpdater.compareAndSet(downstream, 0, 1)) {
                        completeDownstream(downstream, CancelledSubscriptionException.get());
                        // Don't have to bring notifying back to 0 because it's completed.
                    }
                    return;
                }

                if (offsetOfSubscriber == upstreamOffset) {
                    break;
                }

                if (!publishData(downstream)) {
                    break;
                }
            }
        }

        private void completeDownstream(DownstreamSubscription downstream, Throwable cause) {
            downstreamSubscriptions.remove(downstream);
            if (cause == null) {
                try {
                    downstream.subscriber().onComplete();
                } finally {
                    @SuppressWarnings("unchecked")
                    final CompletableFuture<Void> f =
                            (CompletableFuture<Void>) downstream.streamMessage().closeFuture();
                    f.complete(null);
                }
            } else {
                try {
                    if (!(cause instanceof CancelledSubscriptionException)) {
                        downstream.subscriber().onError(cause);
                    }
                } finally {
                    downstream.streamMessage().closeFuture().completeExceptionally(cause);
                }
            }
        }

        private boolean publishData(DownstreamSubscription downstream) {
            for (;;) {
                final long demand = downstream.demand;
                if (demand == 0) {
                    break;
                }

                if (demand == Long.MAX_VALUE ||
                    DownstreamSubscription.demandUpdater.compareAndSet(downstream, demand, demand - 1)) {
                    final Subscriber<Object> subscriber = downstream.subscriber();
                    if (DownstreamSubscription.notifyingUpdater.compareAndSet(downstream, 0, 1)) {
                        T o = contentList.get(downstream.offset++);
                        ReferenceCountUtil.touch(o);
                        if (downstream.withPooledObjects()) {
                            if (o instanceof ByteBufHolder) {
                                o = retainedDuplicate((ByteBufHolder) o);
                            } else if (o instanceof ByteBuf) {
                                o = retainedDuplicate((ByteBuf) o);
                            }
                        } else {
                            if (o instanceof ByteBufHolder) {
                                o = copy((ByteBufHolder) o);
                            } else if (o instanceof ByteBuf) {
                                o = copy((ByteBuf) o);
                            }
                        }

                        subscriber.onNext(o);
                        downstream.notifying = 0;
                        return true;
                    } else {
                        if (demand != Long.MAX_VALUE) {
                            incrementDemand(downstream);
                        }
                        return false;
                    }
                }
            }
            return false;
        }

        @SuppressWarnings("unchecked")
        private T retainedDuplicate(ByteBufHolder o) {
            return (T) o.replace(o.content().retainedDuplicate());
        }

        @SuppressWarnings("unchecked")
        private T retainedDuplicate(ByteBuf o) {
            return (T) o.retainedDuplicate();
        }

        @SuppressWarnings("unchecked")
        private T copy(ByteBufHolder o) {
           return (T) o.replace(Unpooled.copiedBuffer(o.content()));
        }

        @SuppressWarnings("unchecked")
        private T copy(ByteBuf o) {
            return (T) Unpooled.copiedBuffer(o);
        }

        private void incrementDemand(DownstreamSubscription downstream) {
            for (;;) {
                final long oldDemand = downstream.demand;
                if (DownstreamSubscription.demandUpdater.compareAndSet(
                        downstream, oldDemand, oldDemand + 1)) {
                    break;
                }
            }
        }

        boolean isOpen() {
            return state == State.OPEN;
        }

        void close() {
            if (stateUpdater.compareAndSet(this, State.OPEN, State.CLOSED)) {
                state = State.CLOSED;
                final Subscription upstream = this.upstreamSubscription;
                if (upstream != null) {
                    upstream.cancel();
                }
                cleanup();
            }
        }

        private void cleanup() {
            final List<CompletableFuture<Void>> closeFutures = new ArrayList<>();
            downstreamSubscriptions.forEach(s -> {
                @SuppressWarnings("unchecked")
                final CompletableFuture<Void> future = s.streamMessage().closeFuture();
                closeFutures.add(future);
            });
            final CompletableFuture<Void> allDoneFuture =
                    CompletableFuture.allOf(closeFutures.toArray(new CompletableFuture[closeFutures.size()]));
            allDoneFuture.whenComplete((unused, cause) -> {
                contentList.forEach(ReferenceCountUtil::safeRelease);
                contentList.clear();
            });
        }
    }

    private static class ChildStreamMessage<T> implements StreamMessage<T> {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<ChildStreamMessage, DownstreamSubscription>
                subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
                ChildStreamMessage.class, DownstreamSubscription.class, "subscription");

        private StreamMessageProcessor processor;
        @SuppressWarnings("unused")
        private volatile DownstreamSubscription subscription;

        private CompletableFuture<Void> closeFuture = new CompletableFuture<>();

        ChildStreamMessage(StreamMessageProcessor<? extends T> processor) {
            this.processor = processor;
        }

        @Override
        public boolean isOpen() {
            return !closeFuture.isDone();
        }

        @Override
        public boolean isEmpty() {
            return !isOpen() && processor.upstreamOffset == 0;
        }

        @Override
        public CompletableFuture<Void> closeFuture() {
            return closeFuture;
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber) {
            requireNonNull(subscriber, "subscriber");
            subscribe(subscriber, false);
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, boolean withPooledObjects) {
            requireNonNull(subscriber, "subscriber");
            subscribe0(subscriber, null, withPooledObjects);
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, Executor executor) {
            requireNonNull(subscriber, "subscriber");
            requireNonNull(executor, "executor");
            subscribe(subscriber, executor, false);
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, Executor executor,
                              boolean withPooledObjects) {
            requireNonNull(subscriber, "subscriber");
            requireNonNull(executor, "executor");
            subscribe0(subscriber, executor, withPooledObjects);
        }

        private void subscribe0(Subscriber<? super T> subscriber, Executor executor,
                                boolean withPooledObjects) {
            final DownstreamSubscription subscription = new DownstreamSubscription(
                    this, subscriber, processor, executor, withPooledObjects);
            if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
                throw new IllegalStateException(
                        "Subscribed by other subscriber already: " + this.subscription.subscriber());
            }
            processor.subscribe(subscription);
        }

        @Override
        public void abort() {
            final DownstreamSubscription currentSubscription = subscription;
            if (currentSubscription != null) {
                currentSubscription.cancel();
                return;
            }

            final DownstreamSubscription newSubscription = new DownstreamSubscription(
                    this, AbortingSubscriber.INSTANCE, processor, null, false);
            if (subscriptionUpdater.compareAndSet(this, null, newSubscription)) {
                processor.subscribe(newSubscription);
            } else {
                subscription.cancel();
            }
        }
    }

    @VisibleForTesting
    static class DownstreamSubscription implements Subscription {

        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<DownstreamSubscription> subscribedUpdater =
                AtomicIntegerFieldUpdater.newUpdater(DownstreamSubscription.class, "subscribed");

        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<DownstreamSubscription> demandUpdater =
                AtomicLongFieldUpdater.newUpdater(DownstreamSubscription.class, "demand");

        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<DownstreamSubscription> notifyingUpdater =
                AtomicIntegerFieldUpdater.newUpdater(DownstreamSubscription.class, "notifying");

        private final ChildStreamMessage streamMessage;
        private final Subscriber<Object> subscriber;
        private final StreamMessageProcessor processor;
        private final Executor executor;
        private final boolean withPooledObjects;

        @SuppressWarnings("unused")
        private volatile long demand;

        private volatile boolean cancelled;

        @SuppressWarnings("unused")
        private volatile int subscribed; // 0: not on subscribe, 1: on subscribe

        private volatile int offset;

        volatile int notifying;

        private long cumulativeDemand;

        @SuppressWarnings("unchecked")
        DownstreamSubscription(ChildStreamMessage streamMessage,
                               Subscriber<?> subscriber, StreamMessageProcessor processor,
                               Executor executor, boolean withPooledObjects) {
            this.streamMessage = streamMessage;
            this.subscriber = (Subscriber<Object>) subscriber;
            this.processor = processor;
            this.executor = executor;
            this.withPooledObjects = withPooledObjects;
        }

        StreamMessage streamMessage() {
            return streamMessage;
        }

        Subscriber<Object> subscriber() {
            return subscriber;
        }

        Executor executor() {
            return executor;
        }

        boolean withPooledObjects() {
            return withPooledObjects;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                throw new IllegalArgumentException("n: " + n + " (expected: > 0)");
            }

            accumulateDemand(n);
            processor.requestDemand(cumulativeDemand);

            for (;;) {
                final long oldDemand = demand;
                final long newDemand;
                if (oldDemand >= Long.MAX_VALUE - n) {
                    newDemand = Long.MAX_VALUE;
                } else {
                    newDemand = oldDemand + n;
                }

                if (demandUpdater.compareAndSet(this, oldDemand, newDemand)) {
                    if (oldDemand == 0) {
                        processor.notifyDownstream(this);
                    }
                    break;
                }
            }
        }

        private void accumulateDemand(long n) {
            if (n == Long.MAX_VALUE || Long.MAX_VALUE - n >= cumulativeDemand) {
                cumulativeDemand = Long.MAX_VALUE;
            } else {
                cumulativeDemand += n;
            }
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
            }
            if (executor != null) {
                executor.execute(() -> processor.notifyDownstream(this));
            } else {
                processor.notifyDownstream(this);
            }
        }

        boolean setSubscribed() {
            return subscribedUpdater.compareAndSet(this, 0, 1);
        }
    }
}
