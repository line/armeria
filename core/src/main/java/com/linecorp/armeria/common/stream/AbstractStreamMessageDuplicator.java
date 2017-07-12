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

import javax.annotation.Nullable;

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
public abstract class AbstractStreamMessageDuplicator<T, U extends StreamMessage<T>>
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
        if (!processor.isDuplicable()) {
            throw new IllegalStateException("This duplicator has been closed already.");
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
            DUPLICABLE,
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

        private final StreamMessage<T> upstream;
        private final Set<DownstreamSubscription<T>> downstreamSubscriptions =
                Collections.newSetFromMap(new ConcurrentHashMap<>());

        private final List<Object> signals = new ArrayList<>();

        @SuppressWarnings("unused")
        private volatile long requestedDemand;
        private volatile Subscription upstreamSubscription;
        @SuppressWarnings("FieldMayBeFinal")
        private volatile State state = State.DUPLICABLE;

        StreamMessageProcessor(StreamMessage<T> upstream) {
            this.upstream = upstream;
            upstream.subscribe(this, true);
        }

        StreamMessage<T> upstream() {
            return upstream;
        }

        List<Object> signals() {
            return signals;
        }

        @Override
        public void onSubscribe(Subscription s) {
            upstreamSubscription = s;
            downstreamSubscriptions.forEach(DownstreamSubscription::invokeOnSubscribe);
        }

        @Override
        public void onNext(T obj) {
            pushSignal(obj);
        }

        @Override
        public void onError(Throwable cause) {
            if (cause == null) {
                cause = new IllegalStateException("onError() was invoked with null cause.");
            }
            pushSignal(new CloseEvent(cause));
        }

        @Override
        public void onComplete() {
            pushSignal(CloseEvent.SUCCESSFUL_CLOSE);
        }

        private void pushSignal(Object obj) {
            if (signals.size() == Integer.MAX_VALUE) {
                //TODO(minwoox) limit by the size of data not by the size of contentList.
                upstream.abort();
                throw new IllegalStateException("Upstream published more than Integer.MAX_VALUE signals.");
            }

            signals.add(obj);

            if (!downstreamSubscriptions.isEmpty()) {
                downstreamSubscriptions.forEach(DownstreamSubscription::signal);
            }
        }

        void subscribe(DownstreamSubscription<T> subscription) {
            boolean reject = false;
            if (state == State.DUPLICABLE) {
                downstreamSubscriptions.add(subscription);
                if (state == State.CLOSED) {
                    downstreamSubscriptions.remove(subscription);
                    reject = true;
                }
            } else {
                reject = true;
            }

            if (reject) {
                throw new IllegalStateException("duplicator has been closed already.");
            }

            if (upstreamSubscription != null) {
                subscription.invokeOnSubscribe();
            }
        }

        void unsubscribe(DownstreamSubscription<T> subscription, Throwable cause) {
            if (!downstreamSubscriptions.remove(subscription)) {
                return;
            }

            final Subscriber<? super T> subscriber = subscription.subscriber();
            subscription.clearSubscriber();

            final CompletableFuture<Void> closeFuture = subscription.closeFuture();
            if (cause == null) {
                try {
                    subscriber.onComplete();
                } finally {
                    closeFuture.complete(null);
                }
                return;
            }

            try {
                if (!(cause instanceof CancelledSubscriptionException)) {
                    subscriber.onError(cause);
                }
            } finally {
                closeFuture.completeExceptionally(cause);
            }
        }

        void requestDemand(long cumulativeDemand) {
            for (;;) {
                if (cumulativeDemand <= requestedDemand) {
                    break;
                }
                final long currentRequested = requestedDemand;
                if (requestedDemandUpdater.compareAndSet(this, currentRequested, cumulativeDemand)) {
                    upstreamSubscription.request(cumulativeDemand - currentRequested);
                    break;
                }
            }
        }

        boolean isDuplicable() {
            return state == State.DUPLICABLE;
        }

        void close() {
            if (stateUpdater.compareAndSet(this, State.DUPLICABLE, State.CLOSED)) {
                upstream.abort();
                cleanup();
            }
        }

        private void cleanup() {
            final List<CompletableFuture<Void>> closeFutures = new ArrayList<>(downstreamSubscriptions.size());
            downstreamSubscriptions.forEach(s -> {
                final CompletableFuture<Void> future = s.closeFuture();
                closeFutures.add(future);
            });
            final CompletableFuture<Void> allDoneFuture =
                    CompletableFuture.allOf(closeFutures.toArray(new CompletableFuture[closeFutures.size()]));
            allDoneFuture.whenComplete((unused, cause) -> {
                signals.forEach(ReferenceCountUtil::safeRelease);
                signals.clear();
            });
        }
    }

    private static class ChildStreamMessage<T> implements StreamMessage<T> {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<ChildStreamMessage, DownstreamSubscription>
                subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
                ChildStreamMessage.class, DownstreamSubscription.class, "subscription");

        private final StreamMessageProcessor<T> processor;
        @SuppressWarnings("unused")
        private volatile DownstreamSubscription<T> subscription;

        private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

        ChildStreamMessage(StreamMessageProcessor<T> processor) {
            this.processor = processor;
        }

        @Override
        public boolean isOpen() {
            return processor.upstream().isOpen() && !closeFuture.isDone();
        }

        @Override
        public boolean isEmpty() {
            if (isOpen()) {
                return false;
            }

            final DownstreamSubscription<T> subscription = this.subscription;
            return subscription != null && !subscription.publishedAny;
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
            final DownstreamSubscription<T> subscription = new DownstreamSubscription<>(
                    this, subscriber, processor, executor, withPooledObjects);

            if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
                failLateSubscriber(executor, subscriber, this.subscription.subscriber());
                return;
            }

            processor.subscribe(subscription);
        }

        private static void failLateSubscriber(@Nullable Executor executor,
                                               Subscriber<?> lateSubscriber, Subscriber<?> oldSubscriber) {
            final Throwable cause;
            if (oldSubscriber instanceof AbortingSubscriber) {
                cause = AbortedStreamException.get();
            } else {
                cause = new IllegalStateException("subscribed by other subscriber already");
            }

            if (executor != null) {
                executor.execute(() -> {
                    lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
                    lateSubscriber.onError(cause);
                });
            } else {
                lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
                lateSubscriber.onError(cause);
            }
        }

        @Override
        public void abort() {
            final DownstreamSubscription<T> currentSubscription = subscription;
            if (currentSubscription != null) {
                currentSubscription.abort();
                return;
            }

            final DownstreamSubscription<T> newSubscription = new DownstreamSubscription<>(
                    this, AbortingSubscriber.get(), processor, null, false);
            if (subscriptionUpdater.compareAndSet(this, null, newSubscription)) {
                newSubscription.closeFuture().completeExceptionally(AbortedStreamException.get());
            } else {
                subscription.abort();
            }
        }
    }

    @VisibleForTesting
    static class DownstreamSubscription<T> implements Subscription {

        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<DownstreamSubscription> invokedOnSubscribeUpdater =
                AtomicIntegerFieldUpdater.newUpdater(DownstreamSubscription.class, "invokedOnSubscribe");

        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<DownstreamSubscription> demandUpdater =
                AtomicLongFieldUpdater.newUpdater(DownstreamSubscription.class, "demand");

        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<DownstreamSubscription> signalingUpdater =
                AtomicIntegerFieldUpdater.newUpdater(DownstreamSubscription.class, "signaling");

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<DownstreamSubscription, Throwable>
                cancelledOrAbortedUpdater = AtomicReferenceFieldUpdater.newUpdater(
                        DownstreamSubscription.class, Throwable.class, "cancelledOrAborted");

        private final StreamMessage<T> streamMessage;
        private Subscriber<? super T> subscriber;
        private final StreamMessageProcessor<T> processor;
        private final Executor executor;
        private final boolean withPooledObjects;

        @SuppressWarnings("unused")
        private volatile int invokedOnSubscribe; // 0: not invoked onSubscribe, 1: invoked onSubscribe

        @SuppressWarnings("unused")
        private volatile long demand;

        @SuppressWarnings("unused")
        private volatile int signaling; // 0: not signaling, 1: signaling

        /**
         * {@link CancelledSubscriptionException} if cancelled. {@link AbortedStreamException} if aborted.
         */
        @SuppressWarnings("unused")
        private volatile Throwable cancelledOrAborted;

        private int offset;
        private long cumulativeDemand;
        private boolean publishedAny;

        DownstreamSubscription(ChildStreamMessage<T> streamMessage,
                               Subscriber<? super T> subscriber, StreamMessageProcessor<T> processor,
                               Executor executor, boolean withPooledObjects) {
            this.streamMessage = streamMessage;
            this.subscriber = subscriber;
            this.processor = processor;
            this.executor = executor;
            this.withPooledObjects = withPooledObjects;
        }

        CompletableFuture<Void> closeFuture() {
            return streamMessage.closeFuture();
        }

        Subscriber<? super T> subscriber() {
            return subscriber;
        }

        void clearSubscriber() {
            // Replace the subscriber with a placeholder so that it can be garbage-collected and
            // we conform to the Reactive Streams specification rule 3.13.
            subscriber = NeverInvokedSubscriber.get();
        }

        void invokeOnSubscribe() {
            if (!invokedOnSubscribeUpdater.compareAndSet(this, 0, 1)) {
                return;
            }

            if (executor != null) {
                executor.execute(() -> subscriber.onSubscribe(this));
            } else {
                subscriber.onSubscribe(this);
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                final Throwable cause = new IllegalArgumentException(
                        "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)");
                if (executor != null) {
                    executor.execute(() -> subscriber.onError(cause));
                } else {
                    subscriber.onError(cause);
                }
                return;
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
                        signal();
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

        void signal() {
            if (executor == null) {
                doSignal();
            } else {
                executor.execute(this::doSignal);
            }
        }

        private void doSignal() {
            final List<Object> signals = processor.signals();
            while (doSignalSingle(signals)) {
                continue;
            }
        }

        private boolean doSignalSingle(List<Object> signals) {
            if (!beginSignal()) {
                return false;
            }

            try {
                if (cancelledOrAborted != null) {
                    // Stream ended due to cancellation or abortion.
                    processor.unsubscribe(this, cancelledOrAborted);
                    return false;
                }

                if (offset == signals.size()) {
                    // The subscriber read all signals published so far.
                    return false;
                }

                final Object signal = signals.get(offset);
                if (signal instanceof CloseEvent) {
                    // The stream has reached at its end.
                    offset++;
                    processor.unsubscribe(this, ((CloseEvent) signal).cause);
                    return false;
                }

                for (;;) {
                    final long demand = this.demand;
                    if (demand == 0) {
                        break;
                    }

                    if (demand != Long.MAX_VALUE && !demandUpdater.compareAndSet(this, demand, demand - 1)) {
                        // Failed to decrement the demand due to contention.
                        continue;
                    }

                    offset++;
                    @SuppressWarnings("unchecked")
                    T obj = (T) signal;
                    ReferenceCountUtil.touch(obj);
                    if (withPooledObjects) {
                        if (obj instanceof ByteBufHolder) {
                            obj = retainedDuplicate((ByteBufHolder) obj);
                        } else if (obj instanceof ByteBuf) {
                            obj = retainedDuplicate((ByteBuf) obj);
                        }
                    } else {
                        if (obj instanceof ByteBufHolder) {
                            obj = copy((ByteBufHolder) obj);
                        } else if (obj instanceof ByteBuf) {
                            obj = copy((ByteBuf) obj);
                        }
                    }

                    publishedAny = true;
                    subscriber.onNext(obj);
                    return true;
                }
            } finally {
                endSignal();
            }

            return false;
        }

        private boolean beginSignal() {
            return signalingUpdater.compareAndSet(this, 0, 1);
        }

        private void endSignal() {
            signaling = 0;
        }

        @Override
        public void cancel() {
            if (cancelledOrAbortedUpdater.compareAndSet(this, null, CancelledSubscriptionException.get())) {
                signal();
            }
        }

        void abort() {
            if (cancelledOrAbortedUpdater.compareAndSet(this, null, AbortedStreamException.get())) {
                signal();
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> T retainedDuplicate(ByteBufHolder o) {
            return (T) o.replace(o.content().retainedDuplicate());
        }

        @SuppressWarnings("unchecked")
        private static <T> T retainedDuplicate(ByteBuf o) {
            return (T) o.retainedDuplicate();
        }

        @SuppressWarnings("unchecked")
        private static <T> T copy(ByteBufHolder o) {
            return (T) o.replace(Unpooled.copiedBuffer(o.content()));
        }

        @SuppressWarnings("unchecked")
        private static <T> T copy(ByteBuf o) {
            return (T) Unpooled.copiedBuffer(o);
        }
    }

    private static final class CloseEvent {

        static final CloseEvent SUCCESSFUL_CLOSE = new CloseEvent(null);

        private final Throwable cause;

        CloseEvent(Throwable cause) {
            this.cause = cause;
        }

        @Override
        public String toString() {
            if (cause == null) {
                return "CloseEvent";
            } else {
                return "CloseEvent(" + cause + ')';
            }
        }
    }
}
