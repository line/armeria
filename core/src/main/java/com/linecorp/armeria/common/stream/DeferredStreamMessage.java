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

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.CompletionActions;

/**
 * A {@link StreamMessage} whose stream is published later by another {@link StreamMessage}. It is useful when
 * your {@link StreamMessage} will not be instantiated early.
 *
 * @param <T> the type of element signaled
 */
public class DeferredStreamMessage<T> implements StreamMessage<T> {

    @SuppressWarnings({ "AtomicFieldUpdaterIssues", "rawtypes" })
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, StreamMessage> delegateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    DeferredStreamMessage.class, StreamMessage.class, "delegate");

    @SuppressWarnings({ "AtomicFieldUpdaterIssues", "rawtypes" })
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, PendingSubscription>
            pendingSubscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DeferredStreamMessage.class, PendingSubscription.class, "pendingSubscription");

    private static final PendingSubscription<?> ABORTED_SUBSCRIPTION =
            new PendingSubscription<>(new Subscriber<Object>() {
                @Override
                public void onSubscribe(Subscription s) {}

                @Override
                public void onNext(Object o) {}

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onComplete() {}
            }, null);

    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    @SuppressWarnings("unused") // Updated only via delegateUpdater
    private volatile StreamMessage<T> delegate;
    @SuppressWarnings("unused") // Updated only via pendingSubscriptionUpdater
    private volatile PendingSubscription<T> pendingSubscription;
    private volatile boolean abortPending;

    /**
     * Sets the delegate {@link HttpResponse} which will publish the stream actually.
     *
     * @throws IllegalStateException if the delegate has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    protected void delegate(StreamMessage<T> delegate) {
        delegate(delegate, false);
    }

    /**
     * Sets the delegate {@link HttpResponse} which will publish the stream actually, optionally receiving
     * pooled objects from the delegate.
     *
     * @throws IllegalStateException if the delegate has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    protected void delegate(StreamMessage<T> delegate, boolean withPooledObjects) {
        requireNonNull(delegate, "delegate");
        if (!delegateUpdater.compareAndSet(this, null, delegate)) {
            throw new IllegalStateException("delegate set already");
        }

        delegate.closeFuture().handle((unused, cause) -> {
            if (cause == null) {
                closeFuture.complete(null);
            } else {
                closeFuture.completeExceptionally(cause);
            }
            return null;
        }).exceptionally(CompletionActions::log);

        final PendingSubscription<T> pendingSubscription = this.pendingSubscription;
        if (pendingSubscription != null && pendingSubscription != ABORTED_SUBSCRIPTION) {
            subscribeToDelegate(pendingSubscription, withPooledObjects);
        }

        if (abortPending) {
            delegate.abort();
        }
    }

    /**
     * Closes the deferred stream without setting a delegate.
     *
     * @throws IllegalStateException if the delegate has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    public void close() {
        final DefaultStreamMessage<T> m = new DefaultStreamMessage<>();
        m.close();
        delegate(m);
    }

    /**
     * Closes the deferred stream without setting a delegate.
     *
     * @throws IllegalStateException if the delegate has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    public void close(Throwable cause) {
        requireNonNull(cause, "cause");
        final DefaultStreamMessage<T> m = new DefaultStreamMessage<>();
        m.close(cause);
        delegate(m);
    }

    @Override
    public boolean isOpen() {
        return !closeFuture.isDone();
    }

    @Override
    public boolean isEmpty() {
        final StreamMessage<T> delegate = this.delegate;
        if (delegate != null) {
            return delegate.isEmpty();
        }

        return !isOpen();
    }

    @Override
    public CompletableFuture<Void> closeFuture() {
        return closeFuture;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        subscribe0(subscriber, null, false);
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
        subscribe0(subscriber, executor, false);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, Executor executor, boolean withPooledObjects) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        subscribe0(subscriber, executor, withPooledObjects);
    }

    private void subscribe0(Subscriber<? super T> subscriber, Executor executor, boolean withPooledObjects) {
        final PendingSubscription<? super T> newPendingSubscription =
                new PendingSubscription<>(subscriber, executor);

        if (!pendingSubscriptionUpdater.compareAndSet(this, null, newPendingSubscription)) {
            PendingSubscription<? super T> oldPendingSubscription = pendingSubscription;
            if (oldPendingSubscription == ABORTED_SUBSCRIPTION) {
                throw new IllegalStateException("cannot subscribe to an aborted publisher");
            } else {
                throw new IllegalStateException("subscribed by other subscriber already: " +
                                                oldPendingSubscription.subscriber);
            }
        }

        subscribeToDelegate(newPendingSubscription, withPooledObjects);
    }

    private void subscribeToDelegate(PendingSubscription<? super T> pendingSubscription,
                                     boolean withPooledObjects) {
        final StreamMessage<T> delegate = this.delegate;
        if (delegate == null) {
            return;
        }

        if (!pendingSubscription.setSubscribed()) {
            // Subscribed by this method running in another thread.
            return;
        }

        final Subscriber<? super T> subscriber = pendingSubscription.subscriber;
        final Executor executor = pendingSubscription.executor;
        if (executor == null) {
            delegate.subscribe(subscriber, withPooledObjects);
        } else {
            delegate.subscribe(subscriber, executor, withPooledObjects);
        }
    }

    @Override
    public void abort() {
        abortPending = true;

        // Prevent the future subscription.
        pendingSubscriptionUpdater.compareAndSet(this, null, ABORTED_SUBSCRIPTION);

        final StreamMessage<T> delegate = this.delegate;
        if (delegate != null) {
            delegate.abort();
        } else {
            closeFuture.completeExceptionally(CancelledSubscriptionException.get());
        }
    }

    /**
     * {@link Subscriber}, {@link Executor} and whether {@code delegate.subscribe()} was called.
     */
    private static final class PendingSubscription<T> {

        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<PendingSubscription> subscribedUpdater =
                AtomicIntegerFieldUpdater.newUpdater(PendingSubscription.class, "subscribed");

        final Subscriber<T> subscriber;
        final Executor executor;
        @SuppressWarnings("unused")
        volatile int subscribed; // 0 - not subscribed, 1 - subscribed

        PendingSubscription(Subscriber<T> subscriber, Executor executor) {
            this.subscriber = subscriber;
            this.executor = executor;
        }

        boolean setSubscribed() {
            return subscribedUpdater.compareAndSet(this, 0, 1);
        }
    }
}
