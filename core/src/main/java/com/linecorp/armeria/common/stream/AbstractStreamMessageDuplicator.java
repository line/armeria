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

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.abortedOrLate;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsWithPooledObjects;
import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
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
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * Allows subscribing to a {@link StreamMessage} multiple times by duplicating the stream.
 *
 * <p>Only one subscriber can subscribe other stream messages such as {@link DefaultStreamMessage},
 * {@link DeferredStreamMessage}, etc.
 * This factory is wrapping one of those {@link StreamMessage}s and spawns duplicated stream messages
 * which are created using {@link AbstractStreamMessageDuplicator#duplicateStream()} and subscribed
 * by subscribers one by one.</p>
 *
 * <p>The published elements can be shared across {@link Subscriber}s, if you subscribe with the
 * {@link SubscriptionOption#WITH_POOLED_OBJECTS}, so do not manipulate the
 * data unless you copy them.</p>
 *
 * <p>This factory has to be closed by {@link AbstractStreamMessageDuplicator#close()} when
 * you do not need the contents anymore, otherwise memory leak might happen.</p>
 *
 * @param <T> the type of elements
 * @param <U> the type of the upstream {@link StreamMessage} and duplicated {@link StreamMessage}s
 */
public abstract class AbstractStreamMessageDuplicator<T, U extends StreamMessage<T>>
        implements SafeCloseable {

    private final StreamMessageProcessor<T> processor;

    private final EventExecutor duplicatorExecutor;

    /**
     * Creates a new instance which subscribes to the specified upstream {@link StreamMessage} and
     * publishes to multiple subscribers.
     *
     * @param upstream the {@link StreamMessage} who will publish data to subscribers
     * @param signalLengthGetter the signal length getter that produces the length of signals
     * @param executor the executor to use for upstream signals
     * @param maxSignalLength the maximum length of signals. {@code 0} disables the length limit
     */
    protected AbstractStreamMessageDuplicator(
            U upstream, SignalLengthGetter<? super T> signalLengthGetter,
            @Nullable EventExecutor executor, long maxSignalLength) {
        requireNonNull(upstream, "upstream");
        requireNonNull(signalLengthGetter, "signalLengthGetter");
        checkArgument(maxSignalLength >= 0,
                      "maxSignalLength: %s (expected: >= 0)", maxSignalLength);
        if (executor != null) {
            duplicatorExecutor = executor;
        } else {
            final EventLoop currentExecutor = RequestContext.mapCurrent(
                    RequestContext::eventLoop, () -> CommonPools.workerGroup().next());
            assert currentExecutor != null;
            duplicatorExecutor = currentExecutor;
        }

        processor = new StreamMessageProcessor<>(upstream, signalLengthGetter,
                                                 duplicatorExecutor, maxSignalLength);
    }

    /**
     * Creates a new {@link StreamMessage} that duplicates the upstream {@link StreamMessage} specified when
     * creating this duplicator.
     */
    public StreamMessage<T> duplicateStream() {
        return duplicateStream(false);
    }

    /**
     * Creates a new {@link StreamMessage} that duplicates the upstream {@link StreamMessage} specified when
     * creating this duplicator.
     *
     * @param lastStream whether to prevent further duplication
     */
    public StreamMessage<T> duplicateStream(boolean lastStream) {
        if (!processor.isDuplicable()) {
            throw new IllegalStateException("duplicator is closed or last downstream is added.");
        }
        return new ChildStreamMessage<>(this, processor, lastStream);
    }

    /**
     * Returns the default {@link EventExecutor} which will be used when a user subscribes to a child
     * stream using {@link StreamMessage#subscribe(Subscriber, SubscriptionOption...)}.
     */
    protected EventExecutor duplicatorExecutor() {
        return duplicatorExecutor;
    }

    /**
     * Closes this duplicator and prevents it from further duplication.
     * {@link #duplicateStream()} will raise an {@link IllegalStateException} after
     * this method is invoked. Note that the previously {@linkplain #duplicateStream() duplicated streams}
     * will not be closed but will continue publishing data until the upstream {@link StreamMessage}
     * is closed. All the data published from the upstream {@link StreamMessage} are cleaned up when
     * all {@linkplain #duplicateStream() duplicated streams} are complete.
     */
    @Override
    public void close() {
        processor.close();
    }

    /**
     * Closes this duplicator and aborts all stream messages returned by {@link #duplicateStream()}.
     * This will also clean up the data published from the upstream {@link StreamMessage}.
     */
    public void abort() {
        processor.abort(AbortedStreamException::get);
    }

    /**
     * Closes this duplicator and aborts all stream messages returned by {@link #duplicateStream()}
     * with the specified {@link Throwable}.
     * This will also clean up the data published from the upstream {@link StreamMessage}.
     */
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        processor.abort(() -> cause);
    }

    /**
     * Closes this duplicator and aborts all stream messages returned by {@link #duplicateStream()}
     * with a {@link Throwable} which is generated by the specified {@link Supplier}.
     * This will also clean up the data published from the upstream {@link StreamMessage}.
     */
    public void abort(Supplier<? extends Throwable> causeSupplier) {
        processor.abort(requireNonNull(causeSupplier, "causeSupplier"));
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

        private long requestedDemand;
        @Nullable
        private Subscription upstreamSubscription;

        private boolean cancelUpstream;

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
            upstream.subscribe(this, processorExecutor,
                               WITH_POOLED_OBJECTS, SubscriptionOption.NOTIFY_CANCELLATION);
        }

        StreamMessage<T> upstream() {
            return upstream;
        }

        SignalQueue signals() {
            return signals;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (processorExecutor.inEventLoop()) {
                doOnSubscribe(s);
            } else {
                processorExecutor.execute(() -> doOnSubscribe(s));
            }
        }

        private void doOnSubscribe(Subscription s) {
            if (cancelUpstream) {
                s.cancel();
                return;
            }
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
                        final IllegalStateException cause = new IllegalStateException(
                                "signal length greater than the maxSignalLength: " + maxSignalLength);
                        upstream.abort(cause);
                        throw cause;
                    }
                    signalLength += dataLength;
                }
            }

            try {
                final int removedLength = signals.addAndRemoveIfRequested(obj);
                signalLength -= removedLength;
            } catch (IllegalStateException e) {
                upstream.abort(e);
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
                final EventExecutor executor = subscription.executor;
                if (executor.inEventLoop()) {
                    failLateProcessorSubscriber(subscription);
                } else {
                    executor.execute(() -> failLateProcessorSubscriber(subscription));
                }
                return;
            }

            downstreamSubscriptions.add(subscription);
            if (subscription.lastSubscription) {
                state = State.LAST_DOWNSTREAM_ADDED;
            }

            if (upstreamSubscription != null) {
                subscription.invokeOnSubscribe();
            }
        }

        private static void failLateProcessorSubscriber(DownstreamSubscription<?> subscription) {
            final Subscriber<?> lateSubscriber = subscription.subscriber();
            lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
            lateSubscriber.onError(
                    new IllegalStateException("duplicator is closed or no more downstream can be added."));
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
                if (subscription.notifyCancellation || !(cause instanceof CancelledSubscriptionException)) {
                    subscriber.onError(cause);
                }
            } finally {
                completionFuture.completeExceptionally(cause);
                doCleanupIfLastSubscription();
            }
        }

        private void doCleanupIfLastSubscription() {
            if (isLastDownstreamAdded() && downstreamSubscriptions.isEmpty()) {
                state = State.CLOSED;
                doCancelUpstreamSubscription();
                signals.clear();
            }
        }

        private void doCancelUpstreamSubscription() {
            if (upstreamSubscription != null) {
                upstreamSubscription.cancel();
            } else {
                cancelUpstream = true;
            }
        }

        void requestDemand(long cumulativeDemand) {
            if (processorExecutor.inEventLoop()) {
                doRequestDemand(cumulativeDemand);
            } else {
                processorExecutor.execute(() -> doRequestDemand(cumulativeDemand));
            }
        }

        void doRequestDemand(long cumulativeDemand) {
            if (upstreamSubscription == null) {
                return;
            }

            if (cumulativeDemand <= requestedDemand) {
                return;
            }

            final long delta = cumulativeDemand - requestedDemand;
            requestedDemand += delta;
            upstreamSubscription.request(delta);
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
            if (state == State.DUPLICABLE) {
                if (downstreamSubscriptions.isEmpty()) {
                    state = State.CLOSED;
                    // Cancel upstream only when there's no subscriber.
                    doCancelUpstreamSubscription();
                    signals.clear();
                } else {
                    state = State.LAST_DOWNSTREAM_ADDED;
                }
            }
        }

        void abort(Supplier<? extends Throwable> causeSupplier) {
            if (processorExecutor.inEventLoop()) {
                doAbort(causeSupplier);
            } else {
                processorExecutor.execute(() -> doAbort(causeSupplier));
            }
        }

        void doAbort(Supplier<? extends Throwable> causeSupplier) {
            if (state != State.CLOSED) {
                state = State.CLOSED;
                doCancelUpstreamSubscription();
                // Do not call 'upstream.abort();', but 'upstream.cancel()' because this is not aborting
                // the upstream StreamMessage, but aborting duplicator.
                doCleanup(causeSupplier);
            }
        }

        private void doCleanup(Supplier<? extends Throwable> causeSupplier) {
            final List<CompletableFuture<Void>> completionFutures =
                    new ArrayList<>(downstreamSubscriptions.size());
            if (!downstreamSubscriptions.isEmpty()) {
                final Throwable cause = requireNonNull(causeSupplier.get(),
                                                       "cause returned by causeSupplier is null");
                downstreamSubscriptions.forEach(s -> {
                    s.abort(cause);
                    final CompletableFuture<Void> future = s.completionFuture();
                    completionFutures.add(future);
                });
                downstreamSubscriptions.clear();
            }
            CompletableFutures.successfulAsList(completionFutures, unused -> null)
                              .handle((unused1, unused2) -> {
                                  signals.clear();
                                  return null;
                              });
        }
    }

    private static class ChildStreamMessage<T> implements StreamMessage<T> {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<ChildStreamMessage, DownstreamSubscription>
                subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
                ChildStreamMessage.class, DownstreamSubscription.class, "subscription");

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<ChildStreamMessage, Throwable>
                completionCauseUpdater = AtomicReferenceFieldUpdater.newUpdater(
                ChildStreamMessage.class, Throwable.class, "completionCause");

        private final AbstractStreamMessageDuplicator<T, ?> parent;
        private final StreamMessageProcessor<T> processor;
        private final boolean lastStream;

        @Nullable
        @SuppressWarnings("unused")
        private volatile DownstreamSubscription<T> subscription;
        @Nullable
        private volatile Throwable completionCause;

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

        @Nullable
        @Override
        public Throwable completionCause() {
            return completionCause;
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber) {
            subscribe(subscriber, parent.duplicatorExecutor());
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, boolean withPooledObjects) {
            subscribe(subscriber, parent.duplicatorExecutor(), withPooledObjects, false);
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, SubscriptionOption... options) {
            requireNonNull(options, "options");

            final boolean withPooledObjects = containsWithPooledObjects(options);
            final boolean notifyCancellation = containsNotifyCancellation(options);
            subscribe(subscriber, parent.duplicatorExecutor(), withPooledObjects, notifyCancellation);
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
            subscribe(subscriber, executor, false, false);
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                              boolean withPooledObjects) {
            subscribe(subscriber, executor, withPooledObjects, false);
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                              SubscriptionOption... options) {
            requireNonNull(options, "options");

            final boolean withPooledObjects = containsWithPooledObjects(options);
            final boolean notifyCancellation = containsNotifyCancellation(options);
            subscribe(subscriber, executor, withPooledObjects, notifyCancellation);
        }

        private void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                               boolean withPooledObjects, boolean notifyCancellation) {
            requireNonNull(subscriber, "subscriber");
            requireNonNull(executor, "executor");
            final DownstreamSubscription<T> subscription = new DownstreamSubscription<>(
                    this, subscriber, processor, executor, withPooledObjects, notifyCancellation, lastStream);

            if (!subscribe0(subscription)) {
                final DownstreamSubscription<T> oldSubscription = this.subscription;
                assert oldSubscription != null;
                failLateSubscriber(executor, subscriber, oldSubscription.subscriber());
            }
        }

        private boolean subscribe0(DownstreamSubscription<T> subscription) {
            if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
                return false;
            }

            processor.subscribe(subscription);
            return true;
        }

        private static void failLateSubscriber(EventExecutor executor,
                                               Subscriber<?> lateSubscriber, Subscriber<?> oldSubscriber) {
            final Throwable cause = abortedOrLate(oldSubscriber);

            executor.execute(() -> {
                lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
                lateSubscriber.onError(cause);
            });
        }

        @Override
        public CompletableFuture<List<T>> drainAll() {
            return drainAll(parent.duplicatorExecutor());
        }

        @Override
        public CompletableFuture<List<T>> drainAll(boolean withPooledObjects) {
            return drainAll(parent.duplicatorExecutor(), withPooledObjects);
        }

        @Override
        public CompletableFuture<List<T>> drainAll(SubscriptionOption... options) {
            requireNonNull(options, "options");

            final boolean withPooledObjects = containsWithPooledObjects(options);
            return drainAll(parent.duplicatorExecutor(), withPooledObjects);
        }

        @Override
        public CompletableFuture<List<T>> drainAll(EventExecutor executor) {
            return drainAll(executor, false);
        }

        // TODO(minwoox) Make this method private after the deprecated overriden method is removed.
        @Override
        public CompletableFuture<List<T>> drainAll(EventExecutor executor, boolean withPooledObjects) {
            requireNonNull(executor, "executor");

            final StreamMessageDrainer<T> drainer = new StreamMessageDrainer<>(withPooledObjects);
            final DownstreamSubscription<T> subscription = new DownstreamSubscription<>(
                    this, drainer, processor, executor, withPooledObjects,
                    false, /* We do not call Subscription.cancel() in StreamMessageDrainer. */
                    lastStream);
            if (!subscribe0(subscription)) {
                final DownstreamSubscription<T> oldSubscription = this.subscription;
                assert oldSubscription != null;
                return CompletableFutures.exceptionallyCompletedFuture(
                        abortedOrLate(oldSubscription.subscriber()));
            }

            return drainer.future();
        }

        @Override
        public CompletableFuture<List<T>> drainAll(EventExecutor executor, SubscriptionOption... options) {
            requireNonNull(options, "options");

            final boolean withPooledObjects = containsWithPooledObjects(options);
            return drainAll(executor, withPooledObjects);
        }

        @Override
        public void abort() {
            abort0(AbortedStreamException::get);
        }

        @Override
        public void abort(Throwable cause) {
            requireNonNull(cause, "cause");
            abort0(() -> cause);
        }

        @Override
        public void abort(Supplier<? extends Throwable> causeSupplier) {
            requireNonNull(causeSupplier, "causeSupplier");
            abort0(causeSupplier);
        }

        private void abort0(Supplier<? extends Throwable> causeSupplier) {
            final Throwable cause = requireNonNull(causeSupplier.get(),
                                                   "cause returned by causeSupplier is null");
            completionCauseUpdater.compareAndSet(this, null, cause);
            final DownstreamSubscription<T> currentSubscription = subscription;
            if (currentSubscription != null) {
                currentSubscription.abort(cause);
                return;
            }

            final DownstreamSubscription<T> newSubscription = new DownstreamSubscription<>(
                    this, AbortingSubscriber.get(cause), processor, ImmediateEventExecutor.INSTANCE,
                    false, false, false);
            if (subscriptionUpdater.compareAndSet(this, null, newSubscription)) {
                newSubscription.completionFuture().completeExceptionally(cause);
            } else {
                subscription.abort(cause);
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
        private final boolean notifyCancellation;
        final boolean lastSubscription;

        @SuppressWarnings("unused")
        private boolean invokedOnSubscribe;

        @SuppressWarnings("unused")
        private volatile long demand;

        /**
         * {@link CancelledSubscriptionException} if cancelled. {@link Throwable} if aborted.
         */
        @Nullable
        @SuppressWarnings("unused")
        private volatile Throwable cancelledOrAborted;

        private volatile int offset;
        private long cumulativeDemand;
        private boolean inOnNext;

        DownstreamSubscription(ChildStreamMessage<T> streamMessage,
                               Subscriber<? super T> subscriber, StreamMessageProcessor<T> processor,
                               EventExecutor executor, boolean withPooledObjects, boolean notifyCancellation,
                               boolean lastSubscription) {
            this.streamMessage = streamMessage;
            this.subscriber = subscriber;
            this.processor = processor;
            this.executor = executor;
            this.withPooledObjects = withPooledObjects;
            this.notifyCancellation = notifyCancellation;
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
            assert signal != null : "signal is null. offset: " + offset + ", upstreamOffset: " +
                                    processor.upstreamOffset + ", signals: " + signals;

            if (signal instanceof CloseEvent) {
                // The stream has reached at its end.
                offset++;
                processor.unsubscribe(this, ((CloseEvent) signal).cause);
                return false;
            }

            for (;;) {
                final long demand = this.demand;
                if (demand == 0) {
                    return false;
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
                        signals.requestRemovalAheadOf(minOffset);
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
        }

        @Override
        public void cancel() {
            if (cancelledOrAbortedUpdater.compareAndSet(this, null, CancelledSubscriptionException.get())) {
                signal();
            }
        }

        void abort(Throwable cause) {
            if (cancelledOrAbortedUpdater.compareAndSet(this, null, cause)) {
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

        /**
         * Invoked by the executor in {@link StreamMessageProcessor}.
         */
        int addAndRemoveIfRequested(Object o) {
            requireNonNull(o);
            int removedLength = 0;
            if (headOffset < lastRemovalRequestedOffset) {
                removedLength = removeElements();
            }
            final int t = tail;
            final Object[] elements = this.elements;
            assert elements != null : "elements is null. SignalQueue: " + this;

            elements[t] = o;
            size++;
            if ((tail = t + 1 & elements.length - 1) == head) {
                doubleCapacity();
            }
            return removedLength;
        }

        /**
         * Invoked by the executor in {@link StreamMessageProcessor}.
         */
        private int removeElements() {
            final int removalRequestedOffset = lastRemovalRequestedOffset;
            final int numElementsToBeRemoved = removalRequestedOffset - headOffset;
            final Object[] elements = this.elements;
            assert elements != null : "elements is null. SignalQueue: " + this;

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

        /**
         * Invoked by the executor in {@link StreamMessageProcessor}.
         */
        private void doubleCapacity() {
            assert head == tail;
            final int h = head;
            final Object[] elements = this.elements;
            assert elements != null : "elements is null. SignalQueue: " + this;

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

        /**
         * Invoked by the executor in {@link DownstreamSubscription}.
         */
        Object get(int offset) {
            final int head = this.head;
            final int tail = this.tail;
            final Object[] elements = this.elements;
            assert elements != null : "elements is null. SignalQueue: " + this;

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

        /**
         * Invoked by the executor in {@link DownstreamSubscription}.
         */
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

        @Override
        public String toString() {
            final ToStringHelper toStringHelper = toStringHelper(this);
            final Object[] elements = this.elements;
            if (elements != null) {
                toStringHelper.add("elements.length", elements.length);
            }

            return toStringHelper.add("head", head)
                                 .add("tail", tail)
                                 .add("size", size)
                                 .add("headOffset", headOffset)
                                 .add("lastRemovalRequestedOffset", lastRemovalRequestedOffset)
                                 .toString();
        }
    }
}
