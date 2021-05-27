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

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<FixedStreamMessage> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(FixedStreamMessage.class, "subscribed");

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    @Nullable
    private Subscriber<T> subscriber;

    private boolean withPooledObjects;
    private boolean notifyCancellation;
    private boolean completed;

    @Nullable
    private volatile EventExecutor executor;

    @Nullable
    private volatile Throwable abortCause;
    // Updated only via subscribedUpdater
    private volatile int subscribed;

    /**
     * Clean up objects.
     */
    abstract void cleanupObjects(@Nullable Throwable cause);

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
        this.subscriber = (Subscriber<T>) subscriber;
        try {
            subscriber.onSubscribe(this);

            final Throwable abortCause = this.abortCause;
            if (abortCause != null) {
                onError0(abortCause);
            } else if (isEmpty()) {
                onComplete();
            }
        } catch (Throwable t) {
            completed = true;
            cleanupObjects(t);
            onError0(t);
            throwIfFatal(t);
            logger.warn("Subscriber.onSubscribe() should not raise an exception. subscriber: {}",
                        subscriber, t);
        }
    }

    void onNext(T item) {
        assert subscriber != null;
        try {
            if (withPooledObjects) {
                PooledObjects.touch(item);
                subscriber.onNext(item);
            } else {
                subscriber.onNext(PooledObjects.copyAndClose(item));
            }
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
        onError0(cause);
    }

    private void onError0(Throwable cause) {
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
            onError0(cause);
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

        abortCause = cause;
        if (executor == null) {
            // abortCause will be propagated when subscribed
        } else {
            // Subscribed already
            onError0(cause);
        }
    }
}
