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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsWithPooledObjects;
import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A {@link StreamMessage} which only publishes a fixed number of objects known at construction time.
 */
abstract class FixedStreamMessage<T> implements StreamMessage<T>, Subscription {

    private static final Logger logger = LoggerFactory.getLogger(FixedStreamMessage.class);
    private static final Throwable NO_ABORT_CAUSE = new Throwable();

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<FixedStreamMessage> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(FixedStreamMessage.class, "subscribed");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<FixedStreamMessage, Throwable> abortCauseUpdater =
            AtomicReferenceFieldUpdater.newUpdater(FixedStreamMessage.class, Throwable.class, "abortCause");

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    @Nullable
    private volatile Subscriber<T> subscriber;

    @Nullable
    private volatile EventExecutor executor;

    // Updated only via abortCauseUpdater
    @Nullable
    private volatile Throwable abortCause;
    // Updated only via subscribedUpdater
    private volatile int subscribed;

    private boolean withPooledObjects;
    private boolean notifyCancellation;
    private boolean completed;

    /**
     * Clean up objects.
     * @return {@code true} if the objects are cleaned up successfully.
     *         {@code false} if the objects have been cleaned already or no objects to clean.
     */
    abstract void cleanupObjects(@Nullable Throwable cause);

    /**
     * Invoked after an element is removed from the {@link StreamMessage} and before
     * {@link Subscriber#onNext(Object)} is invoked.
     *
     * @param obj the removed element
     */
    protected void onRemoval(T obj) {}

    EventExecutor executor() {
        return firstNonNull(executor, ImmediateEventExecutor.INSTANCE);
    }

    @Override
    public final boolean isOpen() {
        // Fixed streams are closed on construction.
        return false;
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return completionFuture;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");
        if (!subscribedUpdater.compareAndSet(this, 0, 1)) {
            subscriber.onSubscribe(NoopSubscription.get());
            subscriber.onError(new IllegalStateException("subscribed by other subscriber already"));
        } else {
            withPooledObjects = containsWithPooledObjects(options);
            notifyCancellation = containsNotifyCancellation(options);
            this.executor = executor;
            if (executor.inEventLoop()) {
                subscribe0(subscriber);
            } else {
                executor.execute(() -> subscribe0(subscriber));
            }
        }
    }

    private void subscribe0(Subscriber<? super T> subscriber) {
        @SuppressWarnings("unchecked")
        final Subscriber<T> subscriber0 = (Subscriber<T>) subscriber;
        this.subscriber = subscriber0;

        try {
            subscriber.onSubscribe(this);
            if (abortCauseUpdater.compareAndSet(this, null, NO_ABORT_CAUSE)) {
                if (isEmpty()) {
                    onComplete();
                }
            } else {
                onError0(subscriber0, abortCause);
            }
        } catch (Throwable t) {
            completed = true;
            cleanupObjects(t);
            onError0(subscriber0, t);
            throwIfFatal(t);
            logger.warn("Subscriber.onSubscribe() should not raise an exception. subscriber: {}",
                        subscriber, t);
        }
    }

    private T prepareObjectForNotification(T o) {
        if (withPooledObjects) {
            PooledObjects.touch(o);
            return o;
        } else {
            return PooledObjects.copyAndClose(o);
        }
    }

    void onNext(T item) {
        final Subscriber<T> subscriber = this.subscriber;
        assert subscriber != null;
        try {
            onRemoval(item);
            final T published = prepareObjectForNotification(item);
            subscriber.onNext(published);
        } catch (Throwable t) {
            // Just abort this stream so subscriber().onError(e) is called and resources are cleaned up.
            abort0(t);
            throwIfFatal(t);
            logger.warn("Subscriber.onNext({}) should not raise an exception. subscriber: {}",
                        item, subscriber, t);
        }
    }

    void onError(Throwable cause) {
        if (completed) {
            return;
        }
        completed = true;
        onError0(subscriber, cause);
    }

    private void onError0(Subscriber<T> subscriber, Throwable cause) {
        try {
            subscriber.onError(cause);
            completionFuture.completeExceptionally(cause);
        } catch (Throwable t) {
            final Exception composite = new CompositeException(t, cause);
            completionFuture.completeExceptionally(composite);
            throwIfFatal(t);
            logger.warn("Subscriber.onError() should not raise an exception. subscriber: {}",
                        subscriber, composite);
        }
    }

    void onComplete() {
        if (completed) {
            return;
        }
        completed = true;

        final Subscriber<T> subscriber = this.subscriber;
        assert subscriber != null;
        try {
            subscriber.onComplete();
            completionFuture.complete(null);
        } catch (Throwable t) {
            completionFuture.completeExceptionally(t);
            throwIfFatal(t);
            logger.warn("Subscriber.onComplete() should not raise an exception. subscriber: {}",
                        subscriber, t);
        }
    }

    @Override
    public void cancel() {
        final EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            cancel0();
        } else {
            executor.execute(this::cancel0);
        }
    }

    private void cancel0() {
        if (completed) {
            return;
        }
        completed = true;

        final CancelledSubscriptionException cause = CancelledSubscriptionException.get();
        cleanupObjects(cause);

        if (notifyCancellation) {
            onError0(subscriber, cause);
        } else {
            completionFuture.completeExceptionally(cause);
        }
        subscriber = NeverInvokedSubscriber.get();
    }

    @Override
    public void abort() {
        final EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            abort0(null);
        } else {
            executor.execute(() -> abort0(null));
        }
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        final EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            abort0(cause);
        } else {
            executor.execute(() -> abort0(cause));
        }
    }

    private void abort0(@Nullable Throwable cause) {
        if (completed) {
            return;
        }
        completed = true;

        if (cause == null) {
            cause = AbortedStreamException.get();
        }
        cleanupObjects(cause);

        if (abortCauseUpdater.compareAndSet(this, null, cause)) {
            completionFuture.completeExceptionally(cause);
        } else {
            // Subscribed already
            onError0(subscriber, cause);
        }
    }
}
