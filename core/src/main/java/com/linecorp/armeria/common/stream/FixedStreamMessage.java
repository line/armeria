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

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * An {@link AbstractStreamMessage} which only publishes a fixed number of objects known at construction time.
 */
abstract class FixedStreamMessage<T> extends AbstractStreamMessage<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<FixedStreamMessage, SubscriptionImpl>
            subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
            FixedStreamMessage.class, SubscriptionImpl.class, "subscription");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<FixedStreamMessage, CloseEvent>
            closeEventUpdater = AtomicReferenceFieldUpdater.newUpdater(
            FixedStreamMessage.class, CloseEvent.class, "closeEvent");

    @SuppressWarnings("unused")
    @Nullable
    private volatile SubscriptionImpl subscription; // set only via subscriptionUpdater

    @Nullable
    private volatile CloseEvent closeEvent;

    private int requested;

    abstract void cleanupObjects();

    abstract void doRequest(SubscriptionImpl subscription, long n);

    @Nullable
    final CloseEvent closeEvent() {
        return closeEvent;
    }

    final void cleanup(SubscriptionImpl subscription) {
        final CloseEvent closeEvent = this.closeEvent;
        this.closeEvent = null;
        if (closeEvent != null) {
            notifySubscriberOfCloseEvent(subscription, closeEvent);
            // Close event will cleanup.
            return;
        }
        cleanupObjects();
    }

    final int requested() {
        return requested;
    }

    final void setRequested(int n) {
        requested = n;
    }

    @Override
    final void request(long n) {
        final SubscriptionImpl subscription = this.subscription;
        // A user cannot access subscription without subscribing.
        assert subscription != null;

        if (subscription.needsDirectInvocation()) {
            doRequest(subscription, n);
        } else {
            subscription.executor().execute(() -> doRequest(subscription, n));
        }
    }

    @Override
    final long demand() {
        return requested;
    }

    @Override
    public final boolean isOpen() {
        // Fixed streams are closed on construction.
        return false;
    }

    @Override
    final SubscriptionImpl subscribe(SubscriptionImpl subscription) {
        if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
            final SubscriptionImpl oldSubscription = this.subscription;
            assert oldSubscription != null;
            return oldSubscription;
        }

        final Subscriber<Object> subscriber = subscription.subscriber();
        if (subscription.needsDirectInvocation()) {
            subscribe0(subscription, subscriber);
        } else {
            subscription.executor().execute(() -> subscribe0(subscription, subscriber));
        }

        return subscription;
    }

    private void subscribe0(SubscriptionImpl subscription, Subscriber<Object> subscriber) {
        try {
            subscriber.onSubscribe(subscription);
        } catch (Exception e) {
            // Just abort this stream so subscriber().onError(e) is called and resources are cleaned up.
            abort(e);
        }
    }

    final void notifySubscriberOfCloseEvent(SubscriptionImpl subscription, CloseEvent event) {
        try {
            event.notifySubscriber(subscription, whenComplete());
        } finally {
            subscription.clearSubscriber();
            cleanup(subscription);
        }
    }

    @Override
    final void cancel() {
        cancelOrAbort(CancelledSubscriptionException.get());
    }

    @Override
    public final void abort() {
        abort0(AbortedStreamException.get());
    }

    @Override
    public final void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        abort0(cause);
    }

    private void abort0(Throwable cause) {
        final SubscriptionImpl currentSubscription = subscription;
        if (currentSubscription != null) {
            cancelOrAbort(cause);
            return;
        }

        final SubscriptionImpl newSubscription = new SubscriptionImpl(
                this, AbortingSubscriber.get(cause), ImmediateEventExecutor.INSTANCE, false, false);
        subscriptionUpdater.compareAndSet(this, null, newSubscription);
        cancelOrAbort(cause);
    }

    private void cancelOrAbort(Throwable cause) {
        if (closeEventUpdater.compareAndSet(this, null, newCloseEvent(cause))) {
            final SubscriptionImpl subscription = this.subscription;
            assert subscription != null;
            if (subscription.needsDirectInvocation()) {
                cleanup(subscription);
            } else {
                subscription.executor().execute(() -> cleanup(subscription));
            }
        }
    }
}
