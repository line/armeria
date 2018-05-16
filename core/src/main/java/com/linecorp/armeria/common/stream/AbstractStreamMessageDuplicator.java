/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

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

    @SuppressWarnings("rawtypes")
    private static final CompletableFuture[] EMPTY_FUTURES = new CompletableFuture[0];

    private final StreamMessageProcessor<T> processor;

    private final EventExecutor duplicatorExecutor;

    /**
     * Creates a new instance wrapping a {@code publisher} and publishing to multiple subscribers.
     * @param publisher the publisher who will publish data to subscribers
     * @param signalLengthGetter the signal length getter that produces the length of signals
     * @param executor the executor to use for upstream signals
     * @param maxSignalLength the maximum length of signals. {@code 0} disables the length limit
     */
    protected AbstractStreamMessageDuplicator(
            U publisher, SignalLengthGetter<? super T> signalLengthGetter,
            @Nullable EventExecutor executor, long maxSignalLength) {
        requireNonNull(publisher, "publisher");
        requireNonNull(signalLengthGetter, "signalLengthGetter");
        checkArgument(maxSignalLength >= 0,
                      "maxSignalLength: %s (expected: >= 0)", maxSignalLength);
        if (executor != null) {
            duplicatorExecutor = executor;
        } else {
            duplicatorExecutor = RequestContext.mapCurrent(
                    RequestContext::eventLoop, () -> CommonPools.workerGroup().next());
        }

        processor = new StreamMessageProcessor<>(publisher, signalLengthGetter,
                                                 duplicatorExecutor, maxSignalLength);
    }

    /**
     * Creates a new {@link U} instance that publishes data from the {@code publisher} you create
     * this factory with.
     */
    public U duplicateStream() {
        return duplicateStream(false);
    }

    /**
     * Creates a new {@link U} instance that publishes data from the {@code publisher} you create
     * this factory with. If you specify the {@code lastStream} as {@code true}, it will prevent further
     * creation of duplicate stream.
     */
    public U duplicateStream(boolean lastStream) {
        if (!processor.isDuplicable()) {
            throw new IllegalStateException("duplicator is closed or last downstream is added.");
        }
        return doDuplicateStream(new ChildStreamMessage<>(this, processor, lastStream));
    }

    /**
     * Creates a new {@link U} instance that wraps {@link ChildStreamMessage} and forwards its method
     * invocations to it.
     * @param delegate {@link ChildStreamMessage}
     */
    protected abstract U doDuplicateStream(StreamMessage<T> delegate);

    /**
     * Returns the default {@link EventExecutor} which will be used when a user subscribes to a child
     * stream using {@link StreamMessage#subscribe(Subscriber)} or
     * {@link StreamMessage#subscribe(Subscriber, boolean)}.
     */
    protected EventExecutor duplicatorExecutor() {
        return duplicatorExecutor;
    }

    /**
     * Closes this factory and stream messages who are invoked by
     * {@link AbstractStreamMessageDuplicator#duplicateStream()}.
     * Also, clean up the data published from {@code publisher}. If {@link #duplicateStream(boolean)} with
     * {@code true} is called already, invoking this method does not affect and cleaning up occurs when all
     * of the {@link Subscription}s are completed or cancelled.
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
             * {@link AbstractStreamMessageDuplicator#duplicateStream(boolean)} has been called.
             * Will enter {@link #CLOSED}.
             */
            LAST_DOWNSTREAM_ADDED,
            /**
             * {@link AbstractStreamMessageDuplicator#close()} has been called.
             */
            CLOSED
        }

        @SuppressWarnings("rawtypes")
        private static final AtomicLongFieldUpdater<StreamMessageProcessor> requestedDemandUpdater =
                AtomicLongFieldUpdater.newUpdater(StreamMessageProcessor.class, "requestedDemand");

        private final StreamMessage<T> upstream;
        private final SignalQueue signals;
        private final SignalLengthGetter<Object> signalLengthGetter;
        private final EventExecutor processorExecutor;
        private final int maxSignalLength;
        private int signalLength;

        private final Set<DownstreamSubscription<T>> downstreamSubscriptions =
                Collections.newSetFromMap(new ConcurrentHashMap<>());

        volatile int downstreamSignaledCounter;
        volatile int upstreamOffset;

        @SuppressWarnings("unused")
        private volatile long requestedDemand;
        @Nullable
        private volatile Subscription upstreamSubscription;

        private volatile State state = State.DUPLICABLE;

        @SuppressWarnings("unchecked")
        StreamMessageProcessor(StreamMessage<T> upstream, SignalLengthGetter<?> signalLengthGetter,
                               EventExecutor executor, long maxSignalLength) {
            this.upstream = upstream;
            this.signalLengthGetter = (SignalLengthGetter<Object>) signalLengthGetter;
            processorExecutor = executor;
            if (maxSignalLength == 0 || maxSignalLength > Integer.MAX_VALUE) {
                this.maxSignalLength = Integer.MAX_VALUE;
            } else {
                this.maxSignalLength = (int) maxSignalLength;
            }
            signals = new SignalQueue(this.signalLengthGetter);
            upstream.subscribe(this, processorExecutor, true);
        }

        StreamMessage<T> upstream() {
            return upstream;
        }

        SignalQueue signals() {
            return signals;
        }

        @Override
        public void onSubscribe(Subscription s) {
            upstreamSubscription = s;
            if (processorExecutor.inEventLoop()) {
                downstreamSubscriptions.forEach(DownstreamSubscription::invokeOnSubscribe);
            } else {
                processorExecutor.execute(
                        () -> downstreamSubscriptions.forEach(DownstreamSubscription::invokeOnSubscribe));
            }
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
            if (processorExecutor.inEventLoop()) {
                doPushSignal(obj);
            } else {
                processorExecutor.execute(() -> doPushSignal(obj));
            }
        }

        private void doPushSignal(Object obj) {
            if (state == State.CLOSED) {
                ReferenceCountUtil.safeRelease(obj);
                return;
            }
            if (!(obj instanceof CloseEvent)) {
                final int dataLength = signalLengthGetter.length(obj);
                if (dataLength > 0) {
                    final int allowedMaxSignalLength = maxSignalLength - signalLength;
                    if (dataLength > allowedMaxSignalLength) {
                        upstream.abort();
                        throw new IllegalStateException(
                                "signal length greater than the maxSignalLength: " + maxSignalLength);
                    }
                    signalLength += dataLength;
                }
            }

            try {
                final int removedLength = signals.addAndRemoveIfRequested(obj);
                signalLength -= removedLength;
            } catch (IllegalStateException e) {
                upstream.abort();
                throw e;
            }

            upstreamOffset++;

            if (!downstreamSubscriptions.isEmpty()) {
                downstreamSubscriptions.forEach(DownstreamSubscription::signal);
            }
        }

        void subscribe(DownstreamSubscription<T> subscription) {
            if (processorExecutor.inEventLoop()) {
                doSubscribe(subscription);
            } else {
                processorExecutor.execute(() -> doSubscribe(subscription));
            }
        }

        private void doSubscribe(DownstreamSubscription<T> subscription) {
            if (state != State.DUPLICABLE) {
                throw new IllegalStateException("duplicator is closed or last downstream is added.");
            }

            downstreamSubscriptions.add(subscription);
            if (subscription.lastSubscription) {
                state = State.LAST_DOWNSTREAM_ADDED;
            }

            if (upstreamSubscription != null) {
                if (processorExecutor.inEventLoop()) {
                    subscription.invokeOnSubscribe();
                } else {
                    processorExecutor.execute(subscription::invokeOnSubscribe);
                }
            }
        }

        void unsubscribe(DownstreamSubscription<T> subscription, @Nullable Throwable cause) {
            if (processorExecutor.inEventLoop()) {
                doUnsubscribe(subscription, cause);
            } else {
                processorExecutor.execute(() -> doUnsubscribe(subscription, cause));
            }
        }

        private void doUnsubscribe(DownstreamSubscription<T> subscription, @Nullable Throwable cause) {
            if (!downstreamSubscriptions.remove(subscription)) {
                return;
            }

            final Subscriber<? super T> subscriber = subscription.subscriber();
            subscription.clearSubscriber();

            final CompletableFuture<Void> completionFuture = subscription.completionFuture();
            if (cause == null) {
                try {
                    subscriber.onComplete();
                } finally {
                    completionFuture.complete(null);
                    doCleanupIfLastSubscription();
                }
                return;
            }

            try {
                if (!(cause instanceof CancelledSubscriptionException)) {
                    subscriber.onError(cause);
                }
            } finally {
                completionFuture.completeExceptionally(cause);
                doCleanupIfLastSubscription();
            }
        }

        private void doCleanupIfLastSubscription() {
            if (isLastDownstreamAdded() && downstreamSubscriptions.isEmpty()) {
                if (state == State.LAST_DOWNSTREAM_ADDED) {
                    state = State.CLOSED;
                    upstream.abort();
                    signals.clear();
                }
            }
        }

        void requestDemand(long cumulativeDemand) {
            for (;;) {
                final long currentRequested = requestedDemand;
                if (cumulativeDemand <= currentRequested) {
                    break;
                }

                if (requestedDemandUpdater.compareAndSet(this, currentRequested, cumulativeDemand)) {
                    upstreamSubscription.request(cumulativeDemand - currentRequested);
                    break;
                }
            }
        }

        boolean isDuplicable() {
            return state == State.DUPLICABLE;
        }

        boolean isLastDownstreamAdded() {
            return state == State.LAST_DOWNSTREAM_ADDED;
        }

        void close() {
            if (processorExecutor.inEventLoop()) {
                doClose();
            } else {
                processorExecutor.execute(this::doClose);
            }
        }

        void doClose() {
            if (state != State.CLOSED) {
                state = State.CLOSED;
                upstream.abort();
                doCleanup();
            }
        }

        private void doCleanup() {
            final List<CompletableFuture<Void>> completionFutures =
                    new ArrayList<>(downstreamSubscriptions.size());
            downstreamSubscriptions.forEach(s -> {
                s.abort();
                final CompletableFuture<Void> future = s.completionFuture();
                completionFutures.add(future);
            });
            downstreamSubscriptions.clear();
            final CompletableFuture<Void> allDoneFuture = CompletableFuture.allOf(
                    completionFutures.toArray(EMPTY_FUTURES));
            allDoneFuture.whenComplete((unused1, unused2) -> signals.clear());
        }
    }

    private static class ChildStreamMessage<T> implements StreamMessage<T> {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<ChildStreamMessage, DownstreamSubscription>
                subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
                ChildStreamMessage.class, DownstreamSubscription.class, "subscription");

        private final AbstractStreamMessageDuplicator<T, ?> parent;
        private final StreamMessageProcessor<T> processor;
        private final boolean lastStream;

        @Nullable
        @SuppressWarnings("unused")
        private volatile DownstreamSubscription<T> subscription;

        private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        ChildStreamMessage(AbstractStreamMessageDuplicator<T, ?> parent,
                           StreamMessageProcessor<T> processor, boolean lastStream) {
            this.parent = parent;
            this.processor = processor;
            this.lastStream = lastStream;
        }

        @Override
        public boolean isOpen() {
            return processor.upstream().isOpen() && !completionFuture.isDone();
        }

        @Override
        public boolean isEmpty() {
            if (isOpen()) {
                return false;
            }

            return processor.upstream().isEmpty();
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return completionFuture;
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber) {
            requireNonNull(subscriber, "subscriber");
            subscribe(subscriber, parent.duplicatorExecutor(), false);
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, boolean withPooledObjects) {
            requireNonNull(subscriber, "subscriber");
            subscribe0(subscriber, parent.duplicatorExecutor(), withPooledObjects);
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
            subscribe(subscriber, executor, false);
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                              boolean withPooledObjects) {
            requireNonNull(subscriber, "subscriber");
            requireNonNull(executor, "executor");
            subscribe0(subscriber, executor, withPooledObjects);
        }

        private void subscribe0(Subscriber<? super T> subscriber, EventExecutor executor,
                                boolean withPooledObjects) {
            final DownstreamSubscription<T> subscription = new DownstreamSubscription<>(
                    this, subscriber, processor, executor, withPooledObjects, lastStream);

            if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
                failLateSubscriber(executor, subscriber, this.subscription.subscriber());
                return;
            }

            processor.subscribe(subscription);
        }

        private static void failLateSubscriber(EventExecutor executor,
                                               Subscriber<?> lateSubscriber, Subscriber<?> oldSubscriber) {
            final Throwable cause;
            if (oldSubscriber instanceof AbortingSubscriber) {
                cause = AbortedStreamException.get();
            } else {
                cause = new IllegalStateException("subscribed by other subscriber already");
            }

            executor.execute(() -> {
                lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
                lateSubscriber.onError(cause);
            });
        }

        @Override
        public void abort() {
            final DownstreamSubscription<T> currentSubscription = subscription;
            if (currentSubscription != null) {
                currentSubscription.abort();
                return;
            }

            final DownstreamSubscription<T> newSubscription = new DownstreamSubscription<>(
                    this, AbortingSubscriber.get(), processor, ImmediateEventExecutor.INSTANCE, false, false);
            if (subscriptionUpdater.compareAndSet(this, null, newSubscription)) {
                newSubscription.completionFuture().completeExceptionally(AbortedStreamException.get());
            } else {
                subscription.abort();
            }
        }
    }

    @VisibleForTesting
    static class DownstreamSubscription<T> implements Subscription {

        private static final int REQUEST_REMOVAL_THRESHOLD = 50;

        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<DownstreamSubscription> demandUpdater =
                AtomicLongFieldUpdater.newUpdater(DownstreamSubscription.class, "demand");

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<DownstreamSubscription, Throwable>
                cancelledOrAbortedUpdater = AtomicReferenceFieldUpdater.newUpdater(
                        DownstreamSubscription.class, Throwable.class, "cancelledOrAborted");

        private final StreamMessage<T> streamMessage;
        private Subscriber<? super T> subscriber;
        private final StreamMessageProcessor<T> processor;
        private final EventExecutor executor;
        private final boolean withPooledObjects;
        final boolean lastSubscription;

        @SuppressWarnings("unused")
        private boolean invokedOnSubscribe;

        @SuppressWarnings("unused")
        private volatile long demand;

        /**
         * {@link CancelledSubscriptionException} if cancelled. {@link AbortedStreamException} if aborted.
         */
        @Nullable
        @SuppressWarnings("unused")
        private volatile Throwable cancelledOrAborted;

        private volatile int offset;
        private long cumulativeDemand;
        private boolean inOnNext;

        DownstreamSubscription(ChildStreamMessage<T> streamMessage,
                               Subscriber<? super T> subscriber, StreamMessageProcessor<T> processor,
                               EventExecutor executor, boolean withPooledObjects, boolean lastSubscription) {
            this.streamMessage = streamMessage;
            this.subscriber = subscriber;
            this.processor = processor;
            this.executor = executor;
            this.withPooledObjects = withPooledObjects;
            this.lastSubscription = lastSubscription;
        }

        CompletableFuture<Void> completionFuture() {
            return streamMessage.completionFuture();
        }

        Subscriber<? super T> subscriber() {
            return subscriber;
        }

        void clearSubscriber() {
            // Replace the subscriber with a placeholder so that it can be garbage-collected and
            // we conform to the Reactive Streams specification rule 3.13.
            subscriber = NeverInvokedSubscriber.get();
        }

        // only called from processor.processorExecutor
        void invokeOnSubscribe() {
            if (invokedOnSubscribe) {
                return;
            }
            invokedOnSubscribe = true;

            if (executor.inEventLoop()) {
                subscriber.onSubscribe(this);
            } else {
                executor.execute(() -> subscriber.onSubscribe(this));
            }
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                final Throwable cause = new IllegalArgumentException(
                        "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)");
                processor.unsubscribe(this, cause);
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
            if (executor.inEventLoop()) {
                doSignal();
            } else {
                executor.execute(this::doSignal);
            }
        }

        private void doSignal() {
            final SignalQueue signals = processor.signals();
            while (doSignalSingle(signals)) {
                continue;
            }
        }

        private boolean doSignalSingle(SignalQueue signals) {
            if (inOnNext) {
                // Do not let Subscriber.onNext() reenter, because it can lead to weird-looking event ordering
                // for a Subscriber implemented like the following:
                //
                //   public void onNext(Object e) {
                //       subscription.request(1);
                //       ... Handle 'e' ...
                //   }
                //
                // We do not need to worry about synchronizing the access to 'inOnNext' because the subscriber
                // methods must be on the same thread, or synchronized, according to Reactive Streams spec.
                return false;
            }

            if (cancelledOrAborted != null) {
                // Stream ended due to cancellation or abortion.
                processor.unsubscribe(this, cancelledOrAborted);
                return false;
            }

            if (offset == processor.upstreamOffset) {
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
                try {
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
                } catch (Throwable thrown) {
                    // If an exception such as IllegalReferenceCountException is raised while operating
                    // on the ByteBuf, catch it and notify the subscriber with it. So the
                    // subscriber does not hang forever.
                    processor.unsubscribe(this, thrown);
                    return false;
                }

                if (processor.isLastDownstreamAdded()) {
                    if (++processor.downstreamSignaledCounter >= REQUEST_REMOVAL_THRESHOLD) {
                        // don't need to use AtomicBoolean cause it's used for rough counting
                        processor.downstreamSignaledCounter = 0;
                        int minOffset = Integer.MAX_VALUE;
                        for (DownstreamSubscription<?> s : processor.downstreamSubscriptions) {
                            minOffset = Math.min(minOffset, s.offset);
                        }
                        processor.signals().requestRemovalAheadOf(minOffset);
                    }
                }

                inOnNext = true;
                try {
                    subscriber.onNext(obj);
                } finally {
                    inOnNext = false;
                }
                return true;
            }

            return false;
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

        @Nullable
        private final Throwable cause;

        CloseEvent(@Nullable Throwable cause) {
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

    /**
     * A circular queue that stores signals in order and retrieves by {@link #get(int)}.
     * Addition and removal of elements are done by only one thread, or at least once at a time. Reading
     * can be done by multiple threads.
     */
    @VisibleForTesting
    static class SignalQueue {

        private static final AtomicIntegerFieldUpdater<SignalQueue> lastRemovalRequestedOffsetUpdater =
                AtomicIntegerFieldUpdater.newUpdater(SignalQueue.class, "lastRemovalRequestedOffset");

        private final SignalLengthGetter<Object> signalLengthGetter;

        @Nullable
        @VisibleForTesting
        volatile Object[] elements;
        private volatile int head;
        private volatile int tail;
        private volatile int size;

        private int headOffset; // head offset from the first including removed elements
        @SuppressWarnings("unused")
        private volatile int lastRemovalRequestedOffset;

        SignalQueue(SignalLengthGetter<Object> signalLengthGetter) {
            this.signalLengthGetter = signalLengthGetter;
            elements = new Object[16];
        }

        // Steals a reference to o.
        int addAndRemoveIfRequested(Object o) {
            requireNonNull(o);
            int removedLength = 0;
            if (headOffset < lastRemovalRequestedOffset) {
                removedLength = removeElements();
            }
            final int t = tail;
            elements[t] = o;
            size++;
            if ((tail = t + 1 & elements.length - 1) == head) {
                doubleCapacity();
            }
            return removedLength;
        }

        // Releases references to removed elements.
        private int removeElements() {
            final int removalRequestedOffset = lastRemovalRequestedOffset;
            final int numElementsToBeRemoved = removalRequestedOffset - headOffset;
            final int bitMask = elements.length - 1;
            final int oldHead = head;
            int removedLength = 0;
            for (int numRemovals = 0; numRemovals < numElementsToBeRemoved; numRemovals++) {
                final int index = oldHead + numRemovals & bitMask;
                final Object o = elements[index];
                if (!(o instanceof CloseEvent)) {
                    removedLength += signalLengthGetter.length(o);
                }
                ReferenceCountUtil.safeRelease(o);
                elements[index] = null;
            }
            head = oldHead + numElementsToBeRemoved & bitMask;
            headOffset = removalRequestedOffset;
            size -= numElementsToBeRemoved;
            return removedLength;
        }

        private void doubleCapacity() {
            assert head == tail;
            final int h = head;
            final Object[] elements = this.elements;
            final int n = elements.length;
            final int r = n - h; // number of elements to the right of h including h
            final int newCapacity = n << 1;
            if (newCapacity < 0) {
                throw new IllegalStateException("published more than Integer.MAX_VALUE signals.");
            }
            final Object[] a = new Object[newCapacity];
            final int hOffset = headOffset;
            if ((hOffset & newCapacity - 1) == (hOffset & n - 1)) { // even number head wrap-around
                // [4, 5, 6, 3] will be [N, N, N, 3, 4, 5, 6, N]
                System.arraycopy(elements, h, a, h, r); // copy 3
                System.arraycopy(elements, 0, a, n, h); // copy 4,5,6
                tail += n;
            } else { // odd number head wrap-around
                // [8, 5, 6, 7] will be [8, N, N, N, N, 5, 6, 7]
                System.arraycopy(elements, h, a, h + n, r); // copy 5,6,7
                System.arraycopy(elements, 0, a, 0, h); // copy 8
                head = h + n;
            }
            this.elements = a;
        }

        // Steals a reference to the object.
        Object get(int offset) {
            final int head = this.head;
            final int tail = this.tail;
            final int length = elements.length;
            final int convertedIndex = offset & length - 1;
            checkState(size > 0, "queue is empty");
            checkArgument(head < tail ? head <= convertedIndex && convertedIndex < tail
                                      : head <= convertedIndex && convertedIndex < length ||
                                        0 <= convertedIndex && convertedIndex < tail,
                          "offset: %s is invalid. head: %s, tail: %s, capacity: %s ",
                          offset, head, tail, length);
            checkArgument(offset >= lastRemovalRequestedOffset,
                          "offset: %s is invalid. (expected: >= lastRemovalRequestedOffset: %s)",
                          offset, lastRemovalRequestedOffset);
            return elements[convertedIndex];
        }

        void requestRemovalAheadOf(int offset) {
            for (;;) {
                final int oldLastRemovalRequestedOffset = lastRemovalRequestedOffset;
                if (oldLastRemovalRequestedOffset >= offset) {
                    return;
                }
                if (lastRemovalRequestedOffsetUpdater.compareAndSet(
                        this, oldLastRemovalRequestedOffset, offset)) {
                    return;
                }
            }
        }

        int size() {
            return size;
        }

        // Removes references to all objects.
        void clear() {
            final Object[] oldElements = elements;
            if (oldElements == null) {
                return; // Already cleared.
            }
            elements = null;
            final int t = tail;
            for (int i = head; i < t; i++) {
                ReferenceCountUtil.safeRelease(oldElements[i]);
            }
        }
    }
}
