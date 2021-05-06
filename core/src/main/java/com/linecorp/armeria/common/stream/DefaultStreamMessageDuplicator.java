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
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsWithPooledObjects;
import static com.linecorp.armeria.common.stream.SubscriberUtil.abortedOrLate;
import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A default duplicator.
 *
 * @param <T> the type of elements
 * @see StreamMessageDuplicator
 */
@UnstableApi
public class DefaultStreamMessageDuplicator<T> implements StreamMessageDuplicator<T> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultStreamMessageDuplicator.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<DefaultStreamMessageDuplicator> unsubscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultStreamMessageDuplicator.class, "unsubscribed");

    private final StreamMessageProcessor<T> processor;

    private final EventExecutor executor;

    // TODO(minwoox) add a parameter to the constructor that indicates the number of streams to be duplicated.
    //               After the number of duplicates, this duplicator becomes closed automatically.
    private volatile int unsubscribed;

    /**
     * Creates a new instance.
     */
    public DefaultStreamMessageDuplicator(
            StreamMessage<T> upstream, SignalLengthGetter<? super T> signalLengthGetter,
            EventExecutor executor, long maxSignalLength) {
        requireNonNull(upstream, "upstream");
        requireNonNull(signalLengthGetter, "signalLengthGetter");
        this.executor = requireNonNull(executor, "executor");
        checkArgument(maxSignalLength >= 0,
                      "maxSignalLength: %s (expected: >= 0)", maxSignalLength);
        processor = new StreamMessageProcessor<>(this, upstream, signalLengthGetter, executor, maxSignalLength);
    }

    @Override
    public StreamMessage<T> duplicate() {
        if (!processor.isDuplicable()) {
            throw new IllegalStateException("duplicator is closed.");
        }
        unsubscribedUpdater.incrementAndGet(this);
        return new ChildStreamMessage<>(processor);
    }

    /**
     * Returns the default {@link EventExecutor} which will be used when a user subscribes to a child
     * stream using {@link StreamMessage#subscribe(Subscriber, SubscriptionOption...)}.
     */
    protected final EventExecutor duplicatorExecutor() {
        return executor;
    }

    @Override
    public final void close() {
        processor.close();
    }

    @Override
    public final void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public final void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        processor.abort(cause);
    }

    @VisibleForTesting
    static final class StreamMessageProcessor<T> implements Subscriber<T> {

        private enum State {
            DUPLICABLE,
            CLOSED,
            ABORTED
        }

        private final DefaultStreamMessageDuplicator<T> duplicator;
        private final StreamMessage<T> upstream;
        private final SignalQueue signals;
        private final SignalLengthGetter<Object> signalLengthGetter;
        private final EventExecutor executor;
        private final int maxSignalLength;
        private int signalLength;

        private final Set<DownstreamSubscription<T>> downstreamSubscriptions =
                Collections.newSetFromMap(new ConcurrentHashMap<>());

        volatile int downstreamSignaledCounter;
        volatile int upstreamOffset;

        private long requestedDemand;
        @Nullable
        private Subscription upstreamSubscription;
        @Nullable
        private Throwable abortCause;

        private boolean cancelUpstream;

        private volatile State state = State.DUPLICABLE;

        @SuppressWarnings("unchecked")
        StreamMessageProcessor(DefaultStreamMessageDuplicator<T> duplicator, StreamMessage<T> upstream,
                               SignalLengthGetter<?> signalLengthGetter,
                               EventExecutor executor, long maxSignalLength) {
            this.duplicator = duplicator;
            this.upstream = upstream;
            this.signalLengthGetter = (SignalLengthGetter<Object>) signalLengthGetter;
            this.executor = executor;
            if (maxSignalLength == 0 || maxSignalLength > Integer.MAX_VALUE) {
                this.maxSignalLength = Integer.MAX_VALUE;
            } else {
                this.maxSignalLength = (int) maxSignalLength;
            }
            signals = new SignalQueue(this.signalLengthGetter);
            upstream.subscribe(this, executor,
                               SubscriptionOption.WITH_POOLED_OBJECTS, SubscriptionOption.NOTIFY_CANCELLATION);
        }

        StreamMessage<T> upstream() {
            return upstream;
        }

        EventExecutor executor() {
            return executor;
        }

        SignalQueue signals() {
            return signals;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (executor.inEventLoop()) {
                doOnSubscribe(s);
            } else {
                executor.execute(() -> doOnSubscribe(s));
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
            pushSignal(new CloseEvent(cause));
        }

        @Override
        public void onComplete() {
            pushSignal(CloseEvent.SUCCESSFUL_CLOSE);
        }

        private void pushSignal(Object obj) {
            if (executor.inEventLoop()) {
                doPushSignal(obj);
            } else {
                executor.execute(() -> doPushSignal(obj));
            }
        }

        private void doPushSignal(Object obj) {
            if (state == State.ABORTED) {
                StreamMessageUtil.closeOrAbort(obj, abortCause);
                return;
            }
            if (!(obj instanceof CloseEvent)) {
                final int dataLength = signalLengthGetter.length(obj);
                if (dataLength > 0) {
                    final int allowedMaxSignalLength = maxSignalLength - signalLength;
                    if (dataLength > allowedMaxSignalLength) {
                        final ContentTooLargeException cause = ContentTooLargeException.get();
                        upstream.abort(cause);
                        return;
                    }
                    signalLength += dataLength;
                }
            }

            try {
                final int removedLength = signals.addAndRemoveIfRequested(obj);
                signalLength -= removedLength;
            } catch (IllegalStateException e) {
                upstream.abort(e);
                return;
            }

            upstreamOffset++;

            if (!downstreamSubscriptions.isEmpty()) {
                downstreamSubscriptions.forEach(DownstreamSubscription::signal);
            }
        }

        void subscribe(DownstreamSubscription<T> subscription) {
            if (executor.inEventLoop()) {
                doSubscribe(subscription);
            } else {
                executor.execute(() -> doSubscribe(subscription));
            }
        }

        private void doSubscribe(DownstreamSubscription<T> subscription) {
            if (state == State.ABORTED) {
                final EventExecutor executor = subscription.executor;
                if (executor.inEventLoop()) {
                    failLateProcessorSubscriber(subscription);
                } else {
                    executor.execute(() -> failLateProcessorSubscriber(subscription));
                }
                return;
            }

            downstreamSubscriptions.add(subscription);
            unsubscribedUpdater.decrementAndGet(duplicator);
            if (upstreamSubscription != null) {
                subscription.invokeOnSubscribe();
            }
        }

        private static void failLateProcessorSubscriber(DownstreamSubscription<?> subscription) {
            final Subscriber<?> lateSubscriber = subscription.subscriber();
            try {
                lateSubscriber.onSubscribe(NoopSubscription.get());
                lateSubscriber.onError(
                        new IllegalStateException("duplicator is closed or no more downstream can be added."));
            } catch (Throwable t) {
                throwIfFatal(t);
                logger.warn("Subscriber should not throw an exception. subscriber: {}", lateSubscriber, t);
            }
        }

        void unsubscribe(DownstreamSubscription<T> subscription, @Nullable Throwable cause) {
            if (executor.inEventLoop()) {
                doUnsubscribe(subscription, cause);
            } else {
                executor.execute(() -> doUnsubscribe(subscription, cause));
            }
        }

        private void doUnsubscribe(DownstreamSubscription<T> subscription, @Nullable Throwable cause) {
            if (!downstreamSubscriptions.remove(subscription)) {
                return;
            }

            final Subscriber<? super T> subscriber = subscription.subscriber();
            subscription.clearSubscriber();

            final CompletableFuture<Void> completionFuture = subscription.whenComplete();
            if (cause == null) {
                try {
                    subscriber.onComplete();
                    completionFuture.complete(null);
                } catch (Throwable t) {
                    completionFuture.completeExceptionally(t);
                    throwIfFatal(t);
                    logger.warn("Subscriber.onComplete() should not raise an exception. subscriber: {}",
                                subscriber, t);
                } finally {
                    doCleanupIfLastSubscription();
                }
                return;
            }

            try {
                if (subscription.notifyCancellation || !(cause instanceof CancelledSubscriptionException)) {
                    subscriber.onError(cause);
                }
                completionFuture.completeExceptionally(cause);
            } catch (Throwable t) {
                final Exception composite = new CompositeException(t, cause);
                completionFuture.completeExceptionally(composite);
                throwIfFatal(t);
                logger.warn("Subscriber.onError() should not raise an exception. subscriber: {}",
                            subscriber, composite);
            } finally {
                doCleanupIfLastSubscription();
            }
        }

        private void doCleanupIfLastSubscription() {
            if (isClosed() && duplicator.unsubscribed == 0 && downstreamSubscriptions.isEmpty()) {
                // Because the duplicator is closed, we know that unsubscribed will not be incremented
                // anymore and are guaranteed that the last unsubscribed downstream will run this cleanup logic.
                state = State.ABORTED;
                doCancelUpstreamSubscription();
                signals.clear(null);
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
            if (executor.inEventLoop()) {
                doRequestDemand(cumulativeDemand);
            } else {
                executor.execute(() -> doRequestDemand(cumulativeDemand));
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

        boolean isClosed() {
            return state == State.CLOSED;
        }

        void close() {
            if (executor.inEventLoop()) {
                doClose();
            } else {
                executor.execute(this::doClose);
            }
        }

        void doClose() {
            if (state == State.DUPLICABLE) {
                if (duplicator.unsubscribed == 0 && downstreamSubscriptions.isEmpty()) {
                    state = State.ABORTED;
                    doCancelUpstreamSubscription();
                    signals.clear(null);
                } else {
                    state = State.CLOSED;
                }
            }
        }

        void abort(Throwable cause) {
            if (executor.inEventLoop()) {
                doAbort(cause);
            } else {
                executor.execute(() -> doAbort(cause));
            }
        }

        void doAbort(Throwable cause) {
            if (state != State.ABORTED) {
                state = State.ABORTED;
                abortCause = cause;
                doCancelUpstreamSubscription();
                // Do not call 'upstream.abort();', but 'upstream.cancel()' because this is not aborting
                // the upstream StreamMessage, but aborting duplicator.
                doCleanup(cause);
            }
        }

        private void doCleanup(Throwable cause) {
            final List<CompletableFuture<Void>> completionFutures =
                    new ArrayList<>(downstreamSubscriptions.size());
            downstreamSubscriptions.forEach(s -> {
                s.abort(cause);
                final CompletableFuture<Void> future = s.whenComplete();
                completionFutures.add(future);
            });
            downstreamSubscriptions.clear();
            CompletableFutures.successfulAsList(completionFutures, unused -> null)
                              .handle((unused1, unused2) -> {
                                  signals.clear(cause);
                                  return null;
                              });
        }
    }

    private static final class ChildStreamMessage<T> implements StreamMessage<T> {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<ChildStreamMessage, DownstreamSubscription>
                subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
                ChildStreamMessage.class, DownstreamSubscription.class, "subscription");

        private final StreamMessageProcessor<T> processor;

        @Nullable
        @SuppressWarnings("unused")
        private volatile DownstreamSubscription<T> subscription;

        private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

        ChildStreamMessage(StreamMessageProcessor<T> processor) {
            this.processor = processor;
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
        public long demand() {
            return processor.upstream().demand();
        }

        @Override
        public CompletableFuture<Void> whenComplete() {
            return completionFuture;
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
            subscribe(subscriber, executor, false, false);
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
                    this, subscriber, processor, executor, withPooledObjects, notifyCancellation);

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
                try {
                    lateSubscriber.onSubscribe(NoopSubscription.get());
                    lateSubscriber.onError(cause);
                } catch (Throwable t) {
                    throwIfFatal(t);
                    logger.warn("Subscriber should not throw an exception. subscriber: {}", lateSubscriber, t);
                }
            });
        }

        @Override
        public EventExecutor defaultSubscriberExecutor() {
            return processor.executor();
        }

        @Override
        public void abort() {
            abort0(AbortedStreamException.get());
        }

        @Override
        public void abort(Throwable cause) {
            requireNonNull(cause, "cause");
            abort0(cause);
        }

        private void abort0(Throwable cause) {
            DownstreamSubscription<T> currentSubscription = subscription;
            if (currentSubscription != null) {
                currentSubscription.abort(cause);
                return;
            }

            final DownstreamSubscription<T> newSubscription = new DownstreamSubscription<>(
                    this, AbortingSubscriber.get(cause), processor, ImmediateEventExecutor.INSTANCE,
                    false, false);

            if (!subscribe0(newSubscription)) {
                currentSubscription = subscription;
                assert currentSubscription != null;
                currentSubscription.abort(cause);
            }
        }
    }

    static final class DownstreamSubscription<T> implements Subscription {

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
                               EventExecutor executor, boolean withPooledObjects, boolean notifyCancellation) {
            this.streamMessage = streamMessage;
            this.subscriber = subscriber;
            this.processor = processor;
            this.executor = executor;
            this.withPooledObjects = withPooledObjects;
            this.notifyCancellation = notifyCancellation;
        }

        CompletableFuture<Void> whenComplete() {
            return streamMessage.whenComplete();
        }

        Subscriber<? super T> subscriber() {
            return subscriber;
        }

        void clearSubscriber() {
            // Replace the subscriber with a placeholder so that it can be garbage-collected and
            // we conform to the Reactive Streams specification rule 3.13.
            if (!(subscriber instanceof AbortingSubscriber)) {
                subscriber = NeverInvokedSubscriber.get();
            }
        }

        // Called from processor.processorExecutor
        void invokeOnSubscribe() {
            if (invokedOnSubscribe) {
                return;
            }
            invokedOnSubscribe = true;

            if (executor.inEventLoop()) {
                invokeOnSubscribe0();
            } else {
                executor.execute(this::invokeOnSubscribe0);
            }
        }

        // Called from the executor of the subscriber.
        void invokeOnSubscribe0() {
            try {
                subscriber.onSubscribe(this);
            } catch (Throwable t) {
                processor.unsubscribe(this, t);
                throwIfFatal(t);
                logger.warn("Subscriber.onSubscribe() should not raise an exception. subscriber: {}",
                            subscriber, t);
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
                try {
                    if (obj instanceof HttpData) {
                        final HttpData data = (HttpData) obj;
                        if (data.isPooled()) {
                            if (withPooledObjects) {
                                final ByteBuf byteBuf = data.byteBuf(ByteBufAccessMode.RETAINED_DUPLICATE);
                                @SuppressWarnings("unchecked")
                                final T retained = (T) HttpData.wrap(byteBuf)
                                                               .withEndOfStream(data.isEndOfStream());
                                obj = retained;
                            } else {
                                final ByteBuf byteBuf = data.byteBuf();
                                @SuppressWarnings("unchecked")
                                final T copied = (T) HttpData.copyOf(byteBuf)
                                                             .withEndOfStream(data.isEndOfStream());
                                obj = copied;
                            }
                        }
                    }
                } catch (Throwable thrown) {
                    // If an exception such as IllegalReferenceCountException is raised while operating
                    // on the ByteBuf, catch it and notify the subscriber with it. So the
                    // subscriber does not hang forever.
                    processor.unsubscribe(this, thrown);
                    return false;
                }

                if (processor.isClosed() && processor.duplicator.unsubscribed == 0) {
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
                } catch (Throwable t) {
                    processor.unsubscribe(this, t);
                    throwIfFatal(t);
                    logger.warn("Subscriber.onNext({}) should not raise an exception. subscriber: {}",
                                obj, subscriber, t);
                    return false;
                } finally {
                    inOnNext = false;
                }
                return true;
            }
        }

        @Override
        public void cancel() {
            abort(subscriber instanceof AbortingSubscriber ? ((AbortingSubscriber<?>) subscriber).cause()
                                                           : CancelledSubscriptionException.get());
        }

        void abort(Throwable cause) {
            if (cancelledOrAbortedUpdater.compareAndSet(this, null, cause)) {
                signal();
            }
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
    static final class SignalQueue {

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
                PooledObjects.close(o);
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
            checkState(size > 0, "queue is empty.");
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
        void clear(@Nullable Throwable cause) {
            final Object[] oldElements = elements;
            if (oldElements == null) {
                return; // Already cleared.
            }
            elements = null;
            final int t = tail;
            for (int i = head; i < t; i++) {
                StreamMessageUtil.closeOrAbort(oldElements[i], cause);
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
