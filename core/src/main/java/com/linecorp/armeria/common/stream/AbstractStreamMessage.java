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

import static com.linecorp.armeria.common.stream.StreamMessageUtil.abortedOrLate;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsWithPooledObjects;
import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.util.PooledObjects;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;

abstract class AbstractStreamMessage<T> implements StreamMessage<T> {

    static final Logger logger = LoggerFactory.getLogger(AbstractStreamMessage.class);

    static final CloseEvent SUCCESSFUL_CLOSE = new CloseEvent(null);
    static final CloseEvent CANCELLED_CLOSE = new CloseEvent(CancelledSubscriptionException.INSTANCE);
    static final CloseEvent ABORTED_CLOSE = new CloseEvent(AbortedStreamException.INSTANCE);

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, false, false);
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
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
        final SubscriptionImpl subscription =
                new SubscriptionImpl(this, subscriber, executor, withPooledObjects, notifyCancellation);
        final SubscriptionImpl actualSubscription = subscribe(subscription);
        if (actualSubscription != subscription) {
            // Failed to subscribe.
            failLateSubscriber(actualSubscription, subscriber);
        }
    }

    /**
     * Sets the specified {@code subscription} for the current stream.
     *
     * @return the {@link SubscriptionImpl} which is used in actual subscription. If it's not the specified
     *         {@code subscription}, it means the current stream is subscribed by other {@link Subscriber}
     *         or aborted.
     */
    abstract SubscriptionImpl subscribe(SubscriptionImpl subscription);

    @Override
    public final CompletableFuture<List<T>> drainAll(EventExecutor executor) {
        return drainAll(executor, false);
    }

    @Override
    public final CompletableFuture<List<T>> drainAll(EventExecutor executor, SubscriptionOption... options) {
        requireNonNull(options, "options");

        final boolean withPooledObjects = containsWithPooledObjects(options);
        return drainAll(executor, withPooledObjects);
    }

    private CompletableFuture<List<T>> drainAll(EventExecutor executor, boolean withPooledObjects) {
        requireNonNull(executor, "executor");
        final StreamMessageDrainer<T> drainer = new StreamMessageDrainer<>(withPooledObjects);
        final SubscriptionImpl subscription = new SubscriptionImpl(this, drainer, executor,
                                                                   withPooledObjects, false);
        final SubscriptionImpl actualSubscription = subscribe(subscription);

        if (actualSubscription != subscription) {
            // Failed to subscribe.
            return CompletableFutures.exceptionallyCompletedFuture(
                    abortedOrLate(actualSubscription.subscriber()));
        }

        return drainer.future();
    }

    @Override
    public final CompletableFuture<Void> whenComplete() {
        return completionFuture;
    }

    /**
     * Returns the current demand.
     */
    abstract long demand();

    /**
     * Callback invoked by {@link Subscription#request(long)} to add {@code n} to demand.
     */
    abstract void request(long n);

    /**
     * Callback invoked by {@link Subscription#cancel()} to cancel the stream.
     */
    abstract void cancel();

    /**
     * Invoked after an element is removed from the {@link StreamMessage} and before
     * {@link Subscriber#onNext(Object)} is invoked.
     *
     * @param obj the removed element
     */
    protected void onRemoval(T obj) {}

    static void failLateSubscriber(SubscriptionImpl subscription, Subscriber<?> lateSubscriber) {
        final Subscriber<?> oldSubscriber = subscription.subscriber();
        final Throwable cause = abortedOrLate(oldSubscriber);

        if (subscription.needsDirectInvocation()) {
            handleLateSubscriber(lateSubscriber, cause);
        } else {
            subscription.executor().execute(() -> {
                handleLateSubscriber(lateSubscriber, cause);
            });
        }
    }

    private static void handleLateSubscriber(Subscriber<?> lateSubscriber, Throwable cause) {
        try {
            lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
            lateSubscriber.onError(cause);
        } catch (Throwable t) {
            throwIfFatal(t);
            logger.warn("Subscriber should not throw an exception. subscriber: {}", lateSubscriber, t);
        }
    }

    T prepareObjectForNotification(SubscriptionImpl subscription, T o) {
        ReferenceCountUtil.touch(o);
        onRemoval(o);
        if (!subscription.withPooledObjects()) {
            o = PooledObjects.toUnpooled(o);
        }
        return o;
    }

    /**
     * Returns newly created {@link CloseEvent} if the specified {@link Throwable} is not an instance of
     * {@link CancelledSubscriptionException#INSTANCE} or {@link AbortedStreamException#INSTANCE}.
     */
    static CloseEvent newCloseEvent(Throwable cause) {
        if (cause == CancelledSubscriptionException.INSTANCE) {
            return CANCELLED_CLOSE;
        } else if (cause == AbortedStreamException.INSTANCE) {
            return ABORTED_CLOSE;
        } else {
            return new CloseEvent(cause);
        }
    }

    static final class SubscriptionImpl implements Subscription {

        private final AbstractStreamMessage<?> publisher;
        private Subscriber<Object> subscriber;
        private final EventExecutor executor;
        private final boolean withPooledObjects;
        private final boolean notifyCancellation;

        private volatile boolean cancelRequested;

        @SuppressWarnings("unchecked")
        SubscriptionImpl(AbstractStreamMessage<?> publisher, Subscriber<?> subscriber,
                         EventExecutor executor, boolean withPooledObjects, boolean notifyCancellation) {
            this.publisher = publisher;
            this.subscriber = (Subscriber<Object>) subscriber;
            this.executor = executor;
            this.withPooledObjects = withPooledObjects;
            this.notifyCancellation = notifyCancellation;
        }

        Subscriber<Object> subscriber() {
            return subscriber;
        }

        /**
         * Replaces the subscriber with a placeholder so that it can be garbage-collected and
         * we conform to the Reactive Streams specification rule 3.13.
         */
        void clearSubscriber() {
            if (!(subscriber instanceof AbortingSubscriber)) {
                subscriber = NeverInvokedSubscriber.get();
            }
        }

        EventExecutor executor() {
            return executor;
        }

        boolean withPooledObjects() {
            return withPooledObjects;
        }

        boolean notifyCancellation() {
            return notifyCancellation;
        }

        boolean cancelRequested() {
            return cancelRequested;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                // Just abort the publisher so subscriber().onError(e) is called and resources are cleaned up.
                publisher.abort(new IllegalArgumentException(
                        "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
                return;
            }

            publisher.request(n);
        }

        @Override
        public void cancel() {
            cancelRequested = true;
            publisher.cancel();
        }

        // We directly run callbacks for event loops if we're already on the loop, which applies to the vast
        // majority of cases.
        boolean needsDirectInvocation() {
            return executor.inEventLoop();
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(Subscription.class)
                              .add("publisher", publisher)
                              .add("demand", publisher.demand())
                              .add("executor", executor).toString();
        }
    }

    static final class CloseEvent {
        @Nullable
        final Throwable cause;

        CloseEvent(@Nullable Throwable cause) {
            this.cause = cause;
        }

        void notifySubscriber(SubscriptionImpl subscription, CompletableFuture<?> completionFuture) {
            if (completionFuture.isDone()) {
                // Notified already
                return;
            }

            final Subscriber<Object> subscriber = subscription.subscriber();
            Throwable cause = this.cause;
            if (cause == null && subscription.cancelRequested()) {
                cause = CancelledSubscriptionException.get();
            }

            if (cause == null) {
                try {
                    subscriber.onComplete();
                    completionFuture.complete(null);
                } catch (Throwable t) {
                    completionFuture.completeExceptionally(t);
                    throwIfFatal(t);
                    logger.warn("Subscriber.onComplete() should not raise an exception. subscriber: {}",
                                subscriber, t);
                }
            } else {
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
                }
            }
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
