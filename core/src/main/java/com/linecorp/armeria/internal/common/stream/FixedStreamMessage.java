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

package com.linecorp.armeria.internal.common.stream;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsWithPooledObjects;
import static com.linecorp.armeria.internal.common.stream.StreamMessageUtil.touchOrCopyAndClose;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.AggregationSupport;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A {@link StreamMessage} which only publishes a fixed number of objects known at construction time.
 */
public abstract class FixedStreamMessage<T> extends AggregationSupport
        implements StreamMessage<T>, Subscription {

    private static final Logger logger = LoggerFactory.getLogger(FixedStreamMessage.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<FixedStreamMessage, EventExecutor> executorUpdater =
            AtomicReferenceFieldUpdater.newUpdater(FixedStreamMessage.class, EventExecutor.class, "executor");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<FixedStreamMessage, Throwable> abortCauseUpdater =
            AtomicReferenceFieldUpdater.newUpdater(FixedStreamMessage.class, Throwable.class, "abortCause");

    private final CompletableFuture<Void> completionFuture = new EventLoopCheckingFuture<>();

    @Nullable
    private Subscriber<T> subscriber;
    private boolean withPooledObjects;
    private boolean notifyCancellation;
    private boolean completed;

    // Updated only by executorUpdater
    @Nullable
    private volatile EventExecutor executor;

    // Updated only by abortCauseUpdater
    @Nullable
    private volatile Throwable abortCause;

    /**
     * Clean up objects.
     */
    abstract void cleanupObjects(@Nullable Throwable cause);

    abstract List<T> drainAll(boolean withPooledObjects);

    EventExecutor executor() {
        return firstNonNull(executor, ImmediateEventExecutor.INSTANCE);
    }

    @Override
    public boolean isOpen() {
        // Fixed streams are closed on construction except for AggregatingStreamMessage.
        return false;
    }

    @Override
    public boolean isEmpty() {
        // All fixed streams are non-empty except for `EmptyFixedStreamMessage`.
        return false;
    }

    @Override
    public boolean isComplete() {
        return completed || completionFuture.isDone();
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
        if (isOpen()) {
            abortSubscriber(executor, subscriber,
                            new IllegalStateException("a fixed stream is not closed yet"));
            return;
        }

        if (!executorUpdater.compareAndSet(this, null, executor)) {
            final Throwable abortCause = this.abortCause;
            if (abortCause == null) {
                abortSubscriber(executor, subscriber,
                                new IllegalStateException("subscribed by other subscriber already"));
            } else {
                abortSubscriber(executor, subscriber, abortCause);
            }
            return;
        }

        if (executor.inEventLoop()) {
            subscribe0(subscriber, executor, options);
        } else {
            executor.execute(() -> subscribe0(subscriber, executor, options));
        }
    }

    private void subscribe0(Subscriber<? super T> subscriber, EventExecutor executor,
                            SubscriptionOption[] options) {
        //noinspection unchecked
        this.subscriber = (Subscriber<T>) subscriber;
        for (SubscriptionOption option : options) {
            if (option == SubscriptionOption.WITH_POOLED_OBJECTS) {
                withPooledObjects = true;
            } else if (option == SubscriptionOption.NOTIFY_CANCELLATION) {
                notifyCancellation = true;
            }
        }

        if (completed) {
            // A stream is aborted while the method is pending in `executor`.
            final Throwable abortCause = this.abortCause;
            assert abortCause != null;
            abortSubscriber(executor, subscriber, abortCause);
            return;
        }

        try {
            subscriber.onSubscribe(this);
            if (isEmpty()) {
                onComplete();
            }
        } catch (Throwable t) {
            cleanupObjects(t);
            onError(t);
            throwIfFatal(t);
            logger.warn("Subscriber.onSubscribe() should not raise an exception. subscriber: {}",
                        subscriber, t);
        }
    }

    private void abortSubscriber(EventExecutor executor, Subscriber<? super T> subscriber, Throwable cause) {
        if (executor.inEventLoop()) {
            abortSubscriber0(subscriber, cause);
        } else {
            executor.execute(() -> abortSubscriber0(subscriber, cause));
        }
    }

    private void abortSubscriber0(Subscriber<? super T> subscriber, Throwable cause) {
        subscriber.onSubscribe(NoopSubscription.get());
        subscriber.onError(cause);
    }

    @Override
    public CompletableFuture<List<T>> collect(EventExecutor executor, SubscriptionOption... options) {
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");
        final CompletableFuture<List<T>> collectingFuture = new CompletableFuture<>();
        if (executorUpdater.compareAndSet(this, null, executor)) {
            final Throwable abortCause = this.abortCause;
            if (abortCause != null) {
                collectingFuture.completeExceptionally(abortCause);
                return collectingFuture;
            }

            if (executor.inEventLoop()) {
                collect(collectingFuture, executor, options, true);
            } else {
                executor.execute(() -> collect(collectingFuture, executor, options, false));
            }
        } else {
            final Throwable abortCause = this.abortCause;
            if (abortCause != null) {
                collectingFuture.completeExceptionally(abortCause);
            } else {
                collectingFuture.completeExceptionally(
                        new IllegalStateException("subscribed by other subscriber already"));
            }
        }
        return collectingFuture;
    }

    private void collect(CompletableFuture<List<T>> collectingFuture, EventExecutor executor,
                         SubscriptionOption[] options, boolean directExecution) {
        if (completed) {
            // A stream is aborted while the method is pending in `executor`.
            final Throwable abortCause = this.abortCause;
            assert abortCause != null;
            if (directExecution) {
                collectingFuture.completeExceptionally(abortCause);
            } else {
                executor.execute(() -> {
                    collectingFuture.completeExceptionally(abortCause);
                });
            }
            return;
        }

        completed = true;
        final boolean withPooledObjects = containsWithPooledObjects(options);
        collectingFuture.complete(drainAll(withPooledObjects));
        if (directExecution) {
            // The collectingFuture is not returned yet. We can guarantee that whenComplete() will be completed
            // after executing the callbacks of collect() by rescheduling it.
            executor.execute(() -> whenComplete().complete(null));
        } else {
            // We don't know whether the collectingFuture is returned or not at the moment. Just complete
            // whenComplete() immediately.
            whenComplete().complete(null);
        }
    }

    void onNext(T item) {
        assert subscriber != null;
        try {
            subscriber.onNext(touchOrCopyAndClose(item, withPooledObjects));
        } catch (Throwable t) {
            // Just abort this stream so subscriber().onError(e) is called and resources are cleaned up.
            abort1(t, true);
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
            if (!completionFuture.isDone()) {
                completionFuture.completeExceptionally(cause);
            }
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
        abort0(null);
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        abort0(cause);
    }

    private void abort0(@Nullable Throwable cause) {
        if (completed) {
            return;
        }

        final Throwable finalCause = cause != null ? cause : AbortedStreamException.get();
        // Should set `abortCause` before `executor` is written and get after `executor` is written for
        // atomicity.
        if (!abortCauseUpdater.compareAndSet(this, null, finalCause)) {
            // Double abortion
            return;
        }

        if (executorUpdater.compareAndSet(this, null, ImmediateEventExecutor.INSTANCE)) {
            // No subscription was made. Safely clean the resources.
            abort1(finalCause, false);
        } else {
            final EventExecutor executor = this.executor;
            assert executor != null;
            if (executor.inEventLoop()) {
                abort1(finalCause, true);
            } else {
                executor.execute(() -> abort1(finalCause, true));
            }
        }
    }

    private void abort1(Throwable cause, boolean subscribed) {
        if (completed) {
            return;
        }
        completed = true;

        cleanupObjects(cause);
        if (subscribed) {
            final Subscriber<T> subscriber = this.subscriber;
            if (subscriber != null) {
                onError0(cause);
            } else {
                // A subscription is started but `subscribe0()` isn't called yet. Since `completed` is set to
                // true at the beginning of this method, `abortSubscriber()` will propagate `abortCause` via
                // `onError()` when `subscribe0()` is scheduled.
                completionFuture.completeExceptionally(cause);
            }
        } else {
            completionFuture.completeExceptionally(cause);
        }
    }
}
