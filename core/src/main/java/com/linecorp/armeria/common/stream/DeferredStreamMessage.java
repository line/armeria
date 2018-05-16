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

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.util.CompletionActions;

import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A {@link StreamMessage} whose stream is published later by another {@link StreamMessage}. It is useful when
 * your {@link StreamMessage} will not be instantiated early.
 *
 * @param <T> the type of element signaled
 */
public class DeferredStreamMessage<T> extends AbstractStreamMessage<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, SubscriptionImpl>
            subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DeferredStreamMessage.class, SubscriptionImpl.class, "subscription");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, StreamMessage> delegateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(
                    DeferredStreamMessage.class, StreamMessage.class, "delegate");

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<DeferredStreamMessage>
            subscribedToDelegateUpdater =
            AtomicIntegerFieldUpdater.newUpdater(
                    DeferredStreamMessage.class, "subscribedToDelegate");

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<DeferredStreamMessage>
            abortPendingUpdater =
            AtomicIntegerFieldUpdater.newUpdater(
                    DeferredStreamMessage.class, "abortPending");

    @Nullable
    @SuppressWarnings("unused") // Updated only via delegateUpdater
    private volatile StreamMessage<T> delegate;

    // Only accessed from subscription's executor.
    @Nullable
    private Subscription delegateSubscription;

    @Nullable
    @SuppressWarnings("unused") // Updated only via subscriptionUpdater
    private volatile SubscriptionImpl subscription;

    @SuppressWarnings("unused") // Updated only via subscribedToDelegateUpdater
    private volatile int subscribedToDelegate;

    // Only accessed from subscription's executor.
    private long pendingDemand;

    @SuppressWarnings("unused")
    private volatile int abortPending; // 0 - false, 1 - true

    // Only accessed from subscription's executor.
    private boolean cancelPending;

    /**
     * Sets the delegate {@link StreamMessage} which will actually publish the stream.
     *
     * @throws IllegalStateException if the delegate has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    protected void delegate(StreamMessage<T> delegate) {
        requireNonNull(delegate, "delegate");

        if (!delegateUpdater.compareAndSet(this, null, delegate)) {
            throw new IllegalStateException("delegate set already");
        }

        if (abortPending != 0) {
            delegate.abort();
        }

        if (!completionFuture().isDone()) {
            delegate.completionFuture().handle((unused, cause) -> {
                if (cause == null) {
                    completionFuture().complete(null);
                } else {
                    completionFuture().completeExceptionally(cause);
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

        return !completionFuture().isDone();
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
    long demand() {
        return pendingDemand;
    }

    @Override
    void request(long n) {
        // A user cannot access subscription without subscribing.
        assert subscription != null;

        if (subscription.needsDirectInvocation()) {
            doRequest(n);
        } else {
            subscription.executor().execute(() -> doRequest(n));
        }
    }

    private void doRequest(long n) {
        final Subscription delegateSubscription = this.delegateSubscription;
        if (delegateSubscription != null) {
            delegateSubscription.request(n);
        } else {
            pendingDemand += n;
        }
    }

    @Override
    void cancel() {
        // A user cannot access subscription without subscribing.
        final SubscriptionImpl subscription = this.subscription;
        assert subscription != null;

        if (subscription.needsDirectInvocation()) {
            doCancel();
        } else {
            subscription.executor().execute(this::doCancel);
        }
    }

    private void doCancel() {
        final Subscription delegateSubscription = this.delegateSubscription;
        if (delegateSubscription != null) {
            try {
                delegateSubscription.cancel();
            } finally {
                // Clear the subscriber when we become sure that the delegate will not produce events anymore.
                final StreamMessage<T> delegate = this.delegate;
                assert delegate != null;
                if (delegate.isComplete()) {
                    subscription.clearSubscriber();
                } else {
                    delegate.completionFuture().whenComplete((u1, u2) -> subscription.clearSubscriber());
                }
            }
        } else {
            cancelPending = true;
        }
    }

    @Override
    void notifySubscriberOfCloseEvent(SubscriptionImpl subscription, CloseEvent event) {
        // Delegate will notify, don't need to do anything special here.
    }

    @Override
    void subscribe(SubscriptionImpl subscription) {
        final Subscriber<Object> subscriber = subscription.subscriber();
        final Executor executor = subscription.executor();

        if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
            failLateSubscriber(this.subscription, subscriber);
            return;
        }

        if (subscription.needsDirectInvocation()) {
            subscriber.onSubscribe(subscription);
            safeOnSubscribeToDelegate();
        } else {
            executor.execute(() -> {
                subscriber.onSubscribe(subscription);
                safeOnSubscribeToDelegate();
            });
        }
    }

    private void safeOnSubscribeToDelegate() {
        if (delegate == null || subscription == null) {
            return;
        }

        if (!subscribedToDelegateUpdater.compareAndSet(this, 0, 1)) {
            return;
        }

        delegate.subscribe(new ForwardingSubscriber(),
                           subscription.executor(),
                           subscription.withPooledObjects());
    }

    @Override
    public void abort() {
        if (!abortPendingUpdater.compareAndSet(this, 0, 1)) {
            return;
        }

        final SubscriptionImpl newSubscription = new SubscriptionImpl(
                this, AbortingSubscriber.get(), ImmediateEventExecutor.INSTANCE, false);
        subscriptionUpdater.compareAndSet(this, null, newSubscription);

        final StreamMessage<T> delegate = this.delegate;
        if (delegate != null) {
            delegate.abort();
        } else {
            if (subscription.needsDirectInvocation()) {
                ABORTED_CLOSE.notifySubscriber(subscription, completionFuture());
            } else {
                subscription.executor().execute(
                        () -> ABORTED_CLOSE.notifySubscriber(subscription, completionFuture()));
            }
        }
    }

    private final class ForwardingSubscriber implements Subscriber<T> {

        @Override
        public void onSubscribe(Subscription subscription) {
            delegateSubscription = subscription;

            if (cancelPending) {
                delegateSubscription.cancel();
            } else if (pendingDemand > 0) {
                delegateSubscription.request(pendingDemand);
                pendingDemand = 0;
            }
        }

        @Override
        public void onNext(T t) {
            subscription.subscriber().onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            subscription.subscriber().onError(t);
        }

        @Override
        public void onComplete() {
            subscription.subscriber().onComplete();
        }
    }
}
