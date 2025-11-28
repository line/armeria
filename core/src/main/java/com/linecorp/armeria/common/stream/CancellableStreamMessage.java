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

import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.EMPTY_OPTIONS;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsWithPooledObjects;
import static com.linecorp.armeria.internal.common.stream.StreamMessageUtil.touchOrCopyAndClose;
import static com.linecorp.armeria.internal.common.stream.SubscriberUtil.abortedOrLate;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.stream.AbortingSubscriber;
import com.linecorp.armeria.internal.common.stream.NeverInvokedSubscriber;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import io.netty.util.concurrent.EventExecutor;

abstract class CancellableStreamMessage<T> extends AggregationSupport implements StreamMessage<T> {

    static final Logger logger = LoggerFactory.getLogger(CancellableStreamMessage.class);

    static final CloseEvent SUCCESSFUL_CLOSE = new CloseEvent(null);
    static final CloseEvent CANCELLED_CLOSE = new CloseEvent(CancelledSubscriptionException.INSTANCE);
    static final CloseEvent ABORTED_CLOSE = new CloseEvent(AbortedStreamException.INSTANCE);

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, EMPTY_OPTIONS);
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                                SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        final SubscriptionImpl subscription = new SubscriptionImpl(this, subscriber, executor, options);
        final SubscriptionImpl actualSubscription = subscribe(subscription);
        if (actualSubscription != subscription) {
            // Failed to subscribe.
            failLateSubscriber(actualSubscription, subscription);
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
    public final CompletableFuture<Void> whenComplete() {
        return completionFuture;
    }

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

    static void failLateSubscriber(SubscriptionImpl actualSubscription, SubscriptionImpl lateSubscription) {
        final Subscriber<?> actualSubscriber = actualSubscription.subscriber();
        final Subscriber<?> lateSubscriber = lateSubscription.subscriber();
        final Throwable cause = abortedOrLate(actualSubscriber);

        if (lateSubscription.needsDirectInvocation()) {
            handleLateSubscriber(lateSubscriber, cause);
        } else {
            lateSubscription.executor().execute(() -> {
                handleLateSubscriber(lateSubscriber, cause);
            });
        }
    }

    private static void handleLateSubscriber(Subscriber<?> lateSubscriber, Throwable cause) {
        try {
            lateSubscriber.onSubscribe(NoopSubscription.get());
            lateSubscriber.onError(cause);
        } catch (Throwable t) {
            throwIfFatal(t);
            logger.warn("Subscriber should not throw an exception. subscriber: {}", lateSubscriber, t);
        }
    }

    final T prepareObjectForNotification(T o, boolean withPooledObjects) {
        onRemoval(o);
        return touchOrCopyAndClose(o, withPooledObjects);
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

        private final CancellableStreamMessage<?> publisher;
        private Subscriber<Object> subscriber;
        private final EventExecutor executor;
        private final SubscriptionOption[] options;
        private final boolean withPooledObjects;
        private final boolean shouldNotifyCancellation;

        private volatile boolean cancelRequested;

        @SuppressWarnings("unchecked")
        SubscriptionImpl(CancellableStreamMessage<?> publisher, Subscriber<?> subscriber,
                         EventExecutor executor, SubscriptionOption[] options) {
            this.publisher = publisher;
            this.subscriber = (Subscriber<Object>) subscriber;
            this.executor = executor;
            this.options = options;
            withPooledObjects = containsWithPooledObjects(options);
            shouldNotifyCancellation = containsNotifyCancellation(options);
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

        SubscriptionOption[] options() {
            return options;
        }

        boolean withPooledObjects() {
            return withPooledObjects;
        }

        boolean shouldNotifyCancellation() {
            return shouldNotifyCancellation;
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
                              .add("executor", executor)
                              .add("options", options).toString();
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
                    if (subscription.shouldNotifyCancellation() ||
                        !(cause instanceof CancelledSubscriptionException)) {
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
