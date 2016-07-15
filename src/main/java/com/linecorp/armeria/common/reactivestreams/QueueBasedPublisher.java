/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.reactivestreams;

import static java.util.Objects.requireNonNull;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.Exceptions;

public class QueueBasedPublisher<T> implements RichPublisher<T>, Writer<T> {

    private enum State {
        /**
         * The initial state. Will enter {@link #CLOSED} or {@link #CLEANUP}.
         */
        OPEN,
        /**
         * {@link #close()} or {@link #close(Throwable)} has been called. Will enter {@link #CLEANUP} after
         * {@link Subscriber#onComplete()} or {@link Subscriber#onError(Throwable)} is invoked.
         */
        CLOSED,
        /**
         * Anything in the queue must be cleaned up.
         * Enters this state when there's no chance of consumption by subscriber.
         * i.e. when any of the following methods are invoked:
         * <ul>
         *   <li>{@link Subscription#cancel()}</li>
         *   <li>{@link #abort()} (via {@link AbortingSubscriber})</li>
         *   <li>{@link Subscriber#onComplete()}</li>
         *   <li>{@link Subscriber#onError(Throwable)}</li>
         * </ul>
         */
        CLEANUP
    }

    private static final CloseEvent SUCCESSFUL_CLOSE = new CloseEvent(null);
    private static final CloseEvent CANCELLED_CLOSE = new CloseEvent(
            Exceptions.clearTrace(CancelledSubscriptionException.get()));

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<QueueBasedPublisher, SubscriptionImpl> subscriptionUpdater =
            AtomicReferenceFieldUpdater.newUpdater(QueueBasedPublisher.class, SubscriptionImpl.class, "subscription");

    @SuppressWarnings("rawtypes")
    private static final AtomicLongFieldUpdater<QueueBasedPublisher> demandUpdater =
            AtomicLongFieldUpdater.newUpdater(QueueBasedPublisher.class, "demand");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<QueueBasedPublisher, State> stateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(QueueBasedPublisher.class, State.class, "state");

    private final Queue<Object> queue;
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    @SuppressWarnings("unused")
    private volatile SubscriptionImpl subscription; // set only via subscriptionUpdater

    @SuppressWarnings("unused")
    private volatile long demand; // set only via demandUpdater

    @SuppressWarnings("FieldMayBeFinal")
    private volatile State state = State.OPEN;

    public QueueBasedPublisher() {
        this(new ConcurrentLinkedQueue<>());
    }

    public QueueBasedPublisher(Queue<Object> queue) {
        this.queue = requireNonNull(queue, "queue");
    }

    @Override
    public boolean isOpen() {
        return state == State.OPEN;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        subscribe0(new SubscriptionImpl(this, subscriber, null));
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, Executor executor) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        subscribe0(new SubscriptionImpl(this, subscriber, executor));
    }

    private void subscribe0(SubscriptionImpl subscription) {
        if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
            throw new IllegalStateException(
                    "subscribed by other subscriber already: " + this.subscription.subscriber());
        }

        final Executor executor = subscription.executor();
        if (executor != null) {
            executor.execute(() -> subscription.subscriber().onSubscribe(subscription));
        } else {
            subscription.subscriber().onSubscribe(subscription);
        }
    }

    @Override
    public void abort() {
        final SubscriptionImpl currentSubscription = subscription;
        if (currentSubscription != null) {
            currentSubscription.cancel();
            return;
        }

        final SubscriptionImpl newSubscription = new SubscriptionImpl(this, AbortingSubscriber.INSTANCE, null);
        if (subscriptionUpdater.compareAndSet(this, null, newSubscription)) {
            newSubscription.subscriber().onSubscribe(newSubscription);
        } else {
            subscription.cancel();
        }
    }

    @Override
    public boolean write(T obj) {
        requireNonNull(obj, "obj");
        if (!isOpen()) {
            return false;
        }

        pushObject(obj);
        return true;
    }

    @Override
    public boolean write(Supplier<? extends T> supplier) {
        return write(supplier.get());
    }

    @Override
    public CompletableFuture<Void> onDemand(Runnable task) {
        requireNonNull(task, "task");

        final AwaitDemandFuture f = new AwaitDemandFuture();
        if (!isOpen()) {
            f.completeExceptionally(ClosedPublisherException.get());
        } else {
            pushObject(f);
        }

        return f.thenRun(task);
    }

    private void pushObject(Object obj) {
        queue.add(obj);
        notifySubscriber();
    }

    protected void notifySubscriber() {
        final SubscriptionImpl subscription = this.subscription;
        if (subscription == null) {
            return;
        }

        final Queue<Object> queue = this.queue;
        if (queue.isEmpty()) {
            return;
        }

        final Executor executor = subscription.executor();
        if (executor != null) {
            executor.execute(() -> notifySubscribers0(subscription, queue));
        } else {
            notifySubscribers0(subscription, queue);
        }
    }

    private void notifySubscribers0(SubscriptionImpl subscription, Queue<Object> queue) {
        if (state == State.CLEANUP) {
            cleanup();
            return;
        }

        final Subscriber<Object> subscriber = subscription.subscriber();
        for (;;) {
            final Object o = queue.peek();
            if (o == null) {
                break;
            }

            if (o instanceof CloseEvent) {
                notifySubscriberWithCloseEvent(subscriber, (CloseEvent) o);
                break;
            }

            if (o instanceof AwaitDemandFuture) {
                if (notifyCompletableFuture(queue)) {
                    // Notified successfully.
                    continue;
                } else {
                    // Not enough demand.
                    break;
                }
            }

            if (!notifySubscriber(subscriber, queue)) {
                // Not enough demand.
                break;
            }
        }
    }

    private void notifySubscriberWithCloseEvent(Subscriber<Object> subscriber, CloseEvent o) {
        setState(State.CLEANUP);
        cleanup();

        final Throwable cause = o.cause();
        if (cause == null) {
            try {
                subscriber.onComplete();
            } finally {
                closeFuture.complete(null);
            }
        } else {
            try {
                if (!o.isCancelled()) {
                    subscriber.onError(cause);
                }
            } finally {
                closeFuture.completeExceptionally(cause);
            }
        }
    }

    private boolean notifyCompletableFuture(Queue<Object> queue) {
        if (demand == 0) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final CompletableFuture<Void> f = (CompletableFuture<Void>) queue.remove();
        f.complete(null);

        return true;
    }

    private boolean notifySubscriber(Subscriber<Object> subscriber, Queue<Object> queue) {
        for (;;) {
            final long demand = this.demand;
            if (demand == 0) {
                break;
            }

            if (demand == Long.MAX_VALUE || demandUpdater.compareAndSet(this, demand, demand - 1)) {
                @SuppressWarnings("unchecked")
                final T o = (T) queue.remove();
                onRemoval(o);
                subscriber.onNext(o);
                return true;
            }
        }

        return false;
    }

    protected void onRemoval(T obj) {}

    @Override
    public CompletableFuture<Void> closeFuture() {
        return closeFuture;
    }

    @Override
    public void close() {
        if (setState(State.CLOSED)) {
            pushObject(SUCCESSFUL_CLOSE);
        }
    }

    @Override
    public void close(Throwable cause) {
        requireNonNull(cause, "cause");
        if (cause instanceof CancelledSubscriptionException) {
            throw new IllegalArgumentException("cause: " + cause + " (must use Subscription.cancel())");
        }

        if (setState(State.CLOSED)) {
            pushObject(new CloseEvent(cause));
        }
    }

    private boolean setState(State state) {
        assert state != State.OPEN : "state: " + state;
        return stateUpdater.compareAndSet(this, State.OPEN, state);
    }

    private void cleanup() {
        final Throwable cause = ClosedPublisherException.get();
        for (;;) {
            final Object e = queue.poll();
            if (e == null) {
                break;
            }

            if (e instanceof CloseEvent) {
                continue;
            }

            if (e instanceof CompletableFuture) {
                @SuppressWarnings("unchecked")
                final CompletableFuture<Void> f = (CompletableFuture<Void>) e;
                f.completeExceptionally(cause);
            }

            @SuppressWarnings("unchecked")
            T obj = (T) e;
            onRemoval(obj);
        }
    }

    private static final class SubscriptionImpl implements Subscription {

        private final QueueBasedPublisher<?> publisher;
        private final Subscriber<Object> subscriber;
        private final Executor executor;

        @SuppressWarnings("unchecked")
        SubscriptionImpl(QueueBasedPublisher<?> publisher, Subscriber<?> subscriber, Executor executor) {
            this.publisher = publisher;
            this.subscriber = (Subscriber<Object>) subscriber;
            this.executor = executor;
        }

        Subscriber<Object> subscriber() {
            return subscriber;
        }

        Executor executor() {
            return executor;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                throw new IllegalArgumentException("n: " + n + " (expected: > 0)");
            }

            for (;;) {
                final long oldDemand = publisher.demand;
                final long newDemand;
                if (oldDemand >= Long.MAX_VALUE - n) {
                    newDemand = Long.MAX_VALUE;
                } else {
                    newDemand = oldDemand + n;
                }

                if (demandUpdater.compareAndSet(publisher, oldDemand, newDemand)) {
                    if (oldDemand == 0) {
                        publisher.notifySubscriber();
                    }
                    break;
                }
            }
        }

        @Override
        public void cancel() {
            if (publisher.setState(State.CLEANUP)) {
                final CloseEvent closeEvent =
                        Exceptions.isVerbose() ? new CloseEvent(CancelledSubscriptionException.get())
                                               : CANCELLED_CLOSE;

                publisher.pushObject(closeEvent);
            }
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(Subscription.class)
                              .add("publisher", publisher)
                              .add("demand", publisher.demand)
                              .add("executor", executor).toString();
        }
    }

    private static final class AwaitDemandFuture extends CompletableFuture<Void> {}

    private static final class CloseEvent {
        private final Throwable cause;

        CloseEvent(Throwable cause) {
            this.cause = cause;
        }

        boolean isCancelled() {
            return cause instanceof CancelledSubscriptionException;
        }

        Throwable cause() {
            return cause;
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
