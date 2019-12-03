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

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.SafeCloseable;

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
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, Throwable>
            abortCauseUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DeferredStreamMessage.class, Throwable.class, "abortCause");

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

    @Nullable
    private volatile Throwable abortCause;

    // Only accessed from subscription's executor.
    private boolean downstreamOnSubscribeCalled;

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

        final Throwable abortCause = this.abortCause;
        if (abortCause != null) {
            delegate.abort(abortCause);
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
    void request(long n) {
        if (abortCause != null) {
            // This streamMessage is aborted already.
            return;
        }

        // A user cannot access subscription without subscribing.
        final SubscriptionImpl subscription = this.subscription;
        assert subscription != null;

        if (subscription.needsDirectInvocation()) {
            doRequest(n);
        } else {
            subscription.executor().execute(() -> doRequest(n));
        }
    }

    private void doRequest(long n) {
        final Subscription delegateSubscription = this.delegateSubscription;
        assert delegateSubscription != null;
        delegateSubscription.request(n);
    }

    @Override
    void cancel() {
        if (abortCause != null) {
            // This streamMessage is aborted already.
            return;
        }

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
        assert delegateSubscription != null;
        try {
            delegateSubscription.cancel();
        } finally {
            // Clear the subscriber when we become sure that the delegate will not produce events anymore.
            final StreamMessage<T> delegate = this.delegate;
            assert delegate != null;
            if (delegate.isComplete()) {
                subscription.clearSubscriber();
            } else {
                delegate.completionFuture().handle((u1, u2) -> {
                    subscription.clearSubscriber();
                    return null;
                });
            }
        }
    }

    @Override
    void notifySubscriberOfCloseEvent(SubscriptionImpl subscription, CloseEvent event) {
        // Delegate will notify, don't need to do anything special here.
    }

    @Override
    SubscriptionImpl subscribe(SubscriptionImpl subscription) {
        if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
            final SubscriptionImpl oldSubscription = this.subscription;
            assert oldSubscription != null;
            return oldSubscription;
        }

        if (subscription.needsDirectInvocation()) {
            safeOnSubscribeToDelegate();
        } else {
            subscription.executor().execute(this::safeOnSubscribeToDelegate);
        }

        return subscription;
    }

    private void safeOnSubscribeToDelegate() {
        final StreamMessage<T> delegate = this.delegate;
        final SubscriptionImpl subscription = this.subscription;
        if (delegate == null || subscription == null) {
            return;
        }

        if (!subscribedToDelegateUpdater.compareAndSet(this, 0, 1)) {
            return;
        }

        final Builder<SubscriptionOption> builder = ImmutableList.builder();
        if (subscription.withPooledObjects()) {
            builder.add(SubscriptionOption.WITH_POOLED_OBJECTS);
        }
        if (subscription.notifyCancellation()) {
            builder.add(SubscriptionOption.NOTIFY_CANCELLATION);
        }

        delegate.subscribe(new ForwardingSubscriber(subscription),
                           subscription.executor(),
                           builder.build().toArray(new SubscriptionOption[0]));
    }

    @Override
    public void abort() {
        abort0(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        abort0(cause);
    }

    private void abort0(Throwable cause) {
        if (!abortCauseUpdater.compareAndSet(this, null, cause)) {
            return;
        }

        final SubscriptionImpl newSubscription = new SubscriptionImpl(
                this, AbortingSubscriber.get(cause), ImmediateEventExecutor.INSTANCE, false, false);
        subscriptionUpdater.compareAndSet(this, null, newSubscription);

        final StreamMessage<T> delegate = this.delegate;
        if (delegate != null) {
            delegate.abort(cause);
            return;
        }

        final SubscriptionImpl subscription = this.subscription;
        assert subscription != null;
        final CloseEvent closeEvent = newCloseEvent(cause);
        if (subscription.needsDirectInvocation()) {
            abort1(subscription, closeEvent);
        } else {
            subscription.executor().execute(
                    () -> closeEvent.notifySubscriber(subscription, completionFuture()));
        }
    }

    private void abort1(SubscriptionImpl subscription, CloseEvent closeEvent) {
        try (SafeCloseable ignored = pushContextIfExist()) {
            if (!downstreamOnSubscribeCalled) {
                downstreamOnSubscribeCalled = true;
                subscription.subscriber().onSubscribe(subscription);
            }
            closeEvent.notifySubscriber(subscription, completionFuture());
        }
    }

    /**
     * Invoked when {@link Subscriber#onSubscribe(Subscription)} is called by the
     * {@linkplain StreamMessage delegate}. The subclass who wants to push the context before calling callbacks
     * on {@link Subscriber} should override this method and store the context.
     */
    protected void onSubscribeCalled() {}

    private final class ForwardingSubscriber implements Subscriber<T> {
        private final SubscriptionImpl downstreamSubscription;

        ForwardingSubscriber(SubscriptionImpl downstreamSubscription) {
            this.downstreamSubscription = downstreamSubscription;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            onSubscribeCalled();
            delegateSubscription = subscription;
            final Subscriber<Object> subscriber = downstreamSubscription.subscriber();
            if (downstreamSubscription.needsDirectInvocation()) {
                onSubscribe0(subscriber);
            } else {
                downstreamSubscription.executor().execute(() -> {
                    onSubscribe0(subscriber);
                });
            }
        }

        private void onSubscribe0(Subscriber<Object> subscriber) {
            if (downstreamOnSubscribeCalled) {
                // The stream message is aborted after the downstream subscribed and before the delegate calls
                // Subscriber.onSubscribe().
                return;
            }
            downstreamOnSubscribeCalled = true;
            try (SafeCloseable ignored = pushContextIfExist()) {
                subscriber.onSubscribe(downstreamSubscription);
            }
        }

        @Override
        public void onNext(T t) {
            if (downstreamSubscription.needsDirectInvocation()) {
                downstreamSubscription.subscriber().onNext(t);
            } else {
                downstreamSubscription.executor().execute(() -> downstreamSubscription.subscriber().onNext(t));
            }
        }

        @Override
        public void onError(Throwable t) {
            if (downstreamSubscription.needsDirectInvocation()) {
                onError0(t);
            } else {
                downstreamSubscription.executor().execute(() -> onError0(t));
            }
        }

        public void onError0(Throwable t) {
            try (SafeCloseable ignored = pushContextIfExist()) {
                downstreamSubscription.subscriber().onError(t);
            }
        }

        @Override
        public void onComplete() {
            if (downstreamSubscription.needsDirectInvocation()) {
                onComplete0();
            } else {
                downstreamSubscription.executor().execute(this::onComplete0);
            }
        }

        public void onComplete0() {
            try (SafeCloseable ignored = pushContextIfExist()) {
                downstreamSubscription.subscriber().onComplete();
            }
        }
    }
}
