/*
 * Copyright 2016 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.CompletionActions;

/**
 * A {@link StreamMessage} whose stream is published later by another {@link StreamMessage}. It is useful when
 * your {@link StreamMessage} will not be instantiated early.
 *
 * @param <T> the type of element signaled
 */
public class DeferredStreamMessage<T> implements StreamMessage<T> {

    private static final PendingSubscription<Object> NO_OP_ALREADY_SUBSCRIBED =
            new PendingSubscription<>(null, null, false);

    @SuppressWarnings({ "AtomicFieldUpdaterIssues", "rawtypes" })
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, StreamMessage> delegateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    DeferredStreamMessage.class, StreamMessage.class, "delegate");

    @SuppressWarnings({ "AtomicFieldUpdaterIssues", "rawtypes" })
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, PendingSubscription>
            pendingSubscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DeferredStreamMessage.class, PendingSubscription.class, "pendingSubscription");

    private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

    @SuppressWarnings("unused") // Updated only via delegateUpdater
    private volatile StreamMessage<T> delegate;
    @SuppressWarnings("unused") // Updated only via pendingSubscriptionUpdater
    private volatile PendingSubscription<? super T> pendingSubscription;
    private volatile boolean abortPending;

    /**
     * Sets the delegate {@link HttpResponse} which will publish the stream actually.
     *
     * @throws IllegalStateException if the delegate has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    protected void delegate(StreamMessage<T> delegate) {
        requireNonNull(delegate, "delegate");

        if (!delegateUpdater.compareAndSet(this, null, delegate)) {
            throw new IllegalStateException("delegate set already");
        }

        if (abortPending) {
            delegate.abort();
        }

        if (!completionFuture.isDone()) {
            delegate.completionFuture().handle((unused, cause) -> {
                if (cause == null) {
                    completionFuture.complete(null);
                } else {
                    completionFuture.completeExceptionally(cause);
                }
                return null;
            }).exceptionally(CompletionActions::log);
        }

        safeOnSubscribeToDelegate();
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
        final StreamMessage<T> delegate = this.delegate;
        if (delegate != null) {
            return delegate.isOpen();
        }

        return !completionFuture.isDone();
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
    public CompletableFuture<Void> completionFuture() {
        return completionFuture;
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
        if (abortPending) {
            failLateSubscriber(subscriber, executor, AbortedStreamException.get());
            return;
        }

        final PendingSubscription<? super T> newPendingSubscription =
                new PendingSubscription<>(subscriber, executor, withPooledObjects);

        if (!pendingSubscriptionUpdater.compareAndSet(this, null, newPendingSubscription)) {
            failLateSubscriber(subscriber, executor,
                               new IllegalStateException("subscribed by other subscriber already"));
            return;
        }

        safeOnSubscribeToDelegate();
    }

    private void failLateSubscriber(Subscriber<? super T> subscriber, Executor executor, Throwable cause) {
        if (executor == null) {
            try {
                subscriber.onSubscribe(NoopSubscription.INSTANCE);
            } finally {
                subscriber.onError(cause);
            }
        } else {
            executor.execute(() -> {
                try {
                    subscriber.onSubscribe(NoopSubscription.INSTANCE);
                } finally {
                    subscriber.onError(cause);
                }
            });
        }
    }

    private void safeOnSubscribeToDelegate() {

        if (delegate == null) {
            return;
        }

        final PendingSubscription<? super T> subscription = pendingSubscription;

        if (subscription == null || subscription == NO_OP_ALREADY_SUBSCRIBED) {
            return;
        }

        if (!pendingSubscriptionUpdater.compareAndSet(this,
                                                      subscription,
                                                      NO_OP_ALREADY_SUBSCRIBED)) {
            return;
        }

        final Subscriber<? super T> subscriber = subscription.subscriber;
        final Executor executor = subscription.executor;
        final boolean withPooledObjects = subscription.withPooledObjects;
        if (executor == null) {
            delegate.subscribe(subscriber, withPooledObjects);
        } else {
            delegate.subscribe(subscriber, executor, withPooledObjects);
        }
    }

    @Override
    public void abort() {
        abortPending = true;
        final StreamMessage<T> delegate = this.delegate;
        if (delegate != null) {
            delegate.abort();
        } else {
            completionFuture.completeExceptionally(AbortedStreamException.get());
        }
    }

    /**
     * {@link Subscriber} and {@link Executor}.
     */
    private static final class PendingSubscription<T> {
        final Subscriber<T> subscriber;
        final Executor executor;
        final boolean withPooledObjects;

        PendingSubscription(Subscriber<T> subscriber, Executor executor, boolean withPooledObjects) {
            this.subscriber = subscriber;
            this.executor = executor;
            this.withPooledObjects = withPooledObjects;
        }
    }
}
