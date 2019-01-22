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

import static java.util.Objects.requireNonNull;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.PooledObjects;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;

abstract class AbstractStreamMessage<T> implements StreamMessage<T> {

    static final CloseEvent SUCCESSFUL_CLOSE = new CloseEvent(null);
    static final CloseEvent CANCELLED_CLOSE = new CloseEvent(CancelledSubscriptionException.INSTANCE);
    static final CloseEvent ABORTED_CLOSE = new CloseEvent(AbortedStreamException.INSTANCE);

    private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

    @Override
    public final void subscribe(Subscriber<? super T> subscriber) {
        subscribe(subscriber, defaultSubscriberExecutor(), false);
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, boolean withPooledObjects) {
        subscribe(subscriber, defaultSubscriberExecutor(), withPooledObjects);
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, false);
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                                boolean withPooledObjects) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        subscribe(new SubscriptionImpl(this, subscriber, executor, withPooledObjects));
    }

    /**
     * Sets the current subscription for the stream.
     */
    abstract void subscribe(SubscriptionImpl subscription);

    /**
     * Returns the default {@link EventExecutor} which will be used when a user subscribes using
     * {@link #subscribe(Subscriber)} or {@link #subscribe(Subscriber, boolean)}.
     */
    protected EventExecutor defaultSubscriberExecutor() {
        return RequestContext.mapCurrent(RequestContext::eventLoop, () -> CommonPools.workerGroup().next());
    }

    @Override
    public final CompletableFuture<Void> completionFuture() {
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
     * Callback invoked to notify a {@link Subscriber} of a {@link CloseEvent}. The
     * {@link AbstractStreamMessage} needs to ensure the notification happens on the correct thread.
     */
    abstract void notifySubscriberOfCloseEvent(SubscriptionImpl subscription, CloseEvent event);

    /**
     * Invoked after an element is removed from the {@link StreamMessage} and before
     * {@link Subscriber#onNext(Object)} is invoked.
     *
     * @param obj the removed element
     */
    protected void onRemoval(T obj) {}

    static void failLateSubscriber(SubscriptionImpl subscription, Subscriber<?> lateSubscriber) {
        final Subscriber<?> oldSubscriber = subscription.subscriber();
        final Throwable cause;
        if (oldSubscriber instanceof AbortingSubscriber) {
            cause = AbortedStreamException.get();
        } else {
            cause = new IllegalStateException("subscribed by other subscriber already");
        }

        if (subscription.needsDirectInvocation()) {
            lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
            lateSubscriber.onError(cause);
        } else {
            subscription.executor().execute(() -> {
                lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
                lateSubscriber.onError(cause);
            });
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
     * Helper method for the common case of cleaning up all elements in a queue when shutting down the stream.
     */
    void cleanupQueue(SubscriptionImpl subscription, Queue<Object> queue) {
        final Throwable cause = ClosedPublisherException.get();
        for (;;) {
            final Object e = queue.poll();
            if (e == null) {
                break;
            }

            try {
                if (e instanceof CloseEvent) {
                    notifySubscriberOfCloseEvent(subscription, (CloseEvent) e);
                    continue;
                }

                if (e instanceof CompletableFuture) {
                    ((CompletableFuture<?>) e).completeExceptionally(cause);
                }

                @SuppressWarnings("unchecked")
                final T obj = (T) e;
                onRemoval(obj);
            } finally {
                ReferenceCountUtil.safeRelease(e);
            }
        }
    }

    static final class SubscriptionImpl implements Subscription {

        private final AbstractStreamMessage<?> publisher;
        private Subscriber<Object> subscriber;
        private final EventExecutor executor;
        private final boolean withPooledObjects;

        private volatile boolean cancelRequested;

        @SuppressWarnings("unchecked")
        SubscriptionImpl(AbstractStreamMessage<?> publisher, Subscriber<?> subscriber,
                         EventExecutor executor, boolean withPooledObjects) {
            this.publisher = publisher;
            this.subscriber = (Subscriber<Object>) subscriber;
            this.executor = executor;
            this.withPooledObjects = withPooledObjects;
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

        boolean cancelRequested() {
            return cancelRequested;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                invokeOnError(new IllegalArgumentException(
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

        private void invokeOnError(Throwable cause) {
            if (needsDirectInvocation()) {
                subscriber.onError(cause);
            } else {
                executor.execute(() -> subscriber.onError(cause));
            }
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
        private final Throwable cause;

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
                } finally {
                    completionFuture.complete(null);
                }
            } else {
                try {
                    if (!(cause instanceof CancelledSubscriptionException)) {
                        subscriber.onError(cause);
                    }
                } finally {
                    completionFuture.completeExceptionally(cause);
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
