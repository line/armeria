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

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.Flags;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A {@link StreamMessage} used when all the objects that will be published are known at construction time.
 * Reduced synchronization and allocation allow for much higher performance than {@link DefaultStreamMessage},
 * so this class should generally be used when the objects are known.
 */
public final class FixedStreamMessage {

    /**
     * Creates a new {@link StreamMessage} that will publish no objects, just a close event.
     */
    public static <T> StreamMessage<T> of() {
        return new EmptyFixedStreamMessage<>();
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the single {@code obj}.
     */
    public static <T> StreamMessage<T> of(T obj) {
        requireNonNull(obj, "obj");
        return new OneElementFixedStreamMessage<>(obj);
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the two {@code obj1} and {@code obj2}.
     */
    public static <T> StreamMessage<T> of(T obj1, T obj2) {
        requireNonNull(obj1, "obj1");
        requireNonNull(obj2, "obj2");
        return new TwoElementFixedStreamMessage<>(obj1, obj2);
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the given {@code objs}.
     */
    @SafeVarargs
    public static <T> StreamMessage<T> of(T... objs) {
        requireNonNull(objs, "objs");
        switch (objs.length) {
            case 0:
                return of();
            case 1:
                return of(objs[0]);
            case 2:
                return of(objs[0], objs[1]);
            default:
                return new RegularFixedStreamMessage<>(objs);
        }
    }

    abstract static class AbstractFixedStreamMessage<T> extends AbstractStreamMessage<T> {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<AbstractFixedStreamMessage, SubscriptionImpl>
                subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
                AbstractFixedStreamMessage.class, SubscriptionImpl.class, "subscription");

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<AbstractFixedStreamMessage, CloseEvent>
                closeEventUpdater = AtomicReferenceFieldUpdater.newUpdater(
                AbstractFixedStreamMessage.class, CloseEvent.class, "closeEvent");

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
        final void subscribe(SubscriptionImpl subscription) {
            final Subscriber<Object> subscriber = subscription.subscriber();
            final Executor executor = subscription.executor();

            if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
                failLateSubscriber(this.subscription, subscriber);
                return;
            }

            if (subscription.needsDirectInvocation()) {
                subscriber.onSubscribe(subscription);
            } else {
                executor.execute(() -> subscriber.onSubscribe(subscription));
            }
        }

        @Override
        final void notifySubscriberOfCloseEvent(SubscriptionImpl subscription, CloseEvent event) {
            try {
                event.notifySubscriber(subscription, completionFuture());
            } finally {
                subscription.clearSubscriber();
                cleanup(subscription);
            }
        }

        @Override
        final void cancel() {
            cancelOrAbort(true);
        }

        @Override
        public final void abort() {
            final SubscriptionImpl currentSubscription = subscription;
            if (currentSubscription != null) {
                cancelOrAbort(false);
                return;
            }

            final SubscriptionImpl newSubscription = new SubscriptionImpl(
                    this, AbortingSubscriber.get(), ImmediateEventExecutor.INSTANCE, false);
            subscriptionUpdater.compareAndSet(this, null, newSubscription);
            cancelOrAbort(false);
        }

        private void cancelOrAbort(boolean cancel) {
            final CloseEvent closeEvent;
            if (cancel) {
                closeEvent = Flags.verboseExceptions() ?
                             new CloseEvent(CancelledSubscriptionException.get()) : CANCELLED_CLOSE;
            } else {
                closeEvent = Flags.verboseExceptions() ?
                             new CloseEvent(AbortedStreamException.get()) : ABORTED_CLOSE;
            }
            if (closeEventUpdater.compareAndSet(this, null, closeEvent)) {
                if (subscription.needsDirectInvocation()) {
                    cleanup(subscription);
                } else {
                    subscription.executor().execute(() -> cleanup(subscription));
                }
            }
        }
    }

    public static class EmptyFixedStreamMessage<T> extends AbstractFixedStreamMessage<T> {

        // No objects, so just notify of close as soon as there is demand.
        @Override
        final void doRequest(SubscriptionImpl subscription, long unused) {
            if (requested() != 0) {
                // Already have demand so don't need to do anything.
                return;
            }
            setRequested(1);
            notifySubscriberOfCloseEvent(subscription, SUCCESSFUL_CLOSE);
        }

        @Override
        public final boolean isEmpty() {
            return true;
        }

        @Override
        final void cleanupObjects() {
            // Empty streams have no objects to clean.
        }
    }

    public static class OneElementFixedStreamMessage<T> extends AbstractFixedStreamMessage<T> {

        private T obj;

        protected OneElementFixedStreamMessage(T obj) {
            this.obj = obj;
        }

        @Override
        final void cleanupObjects() {
            if (obj != null) {
                try {
                    onRemoval(obj);
                } finally {
                    ReferenceCountUtil.safeRelease(obj);
                }
                obj = null;
            }
        }

        @Override
        final void doRequest(SubscriptionImpl subscription, long unused) {
            if (requested() != 0) {
                // Already have demand, so don't need to do anything, the current demand will complete the
                // stream.
                return;
            }
            setRequested(1);
            doNotify(subscription);
        }

        @Override
        public final boolean isEmpty() {
            return false;
        }

        private void doNotify(SubscriptionImpl subscription) {
            // Only called with correct demand, so no need to even check it.
            T published = prepareObjectForNotification(subscription, obj);
            obj = null;
            // Not possible to have re-entrant onNext with only one item, so no need to keep track of it.
            subscription.subscriber().onNext(published);
            notifySubscriberOfCloseEvent(subscription, SUCCESSFUL_CLOSE);
        }
    }

    public static class TwoElementFixedStreamMessage<T> extends AbstractFixedStreamMessage<T> {

        private T obj1;
        private T obj2;

        private boolean inOnNext;

        /**
         * Constructs a new {@link TwoElementFixedStreamMessage} for the given objects.
         */
        protected TwoElementFixedStreamMessage(T obj1, T obj2) {
            this.obj1 = obj1;
            this.obj2 = obj2;
        }

        @Override
        final void cleanupObjects() {
            if (obj1 != null) {
                try {
                    onRemoval(obj1);
                } finally {
                    ReferenceCountUtil.safeRelease(obj1);
                }
                obj1 = null;
            }
            if (obj2 != null) {
                try {
                    onRemoval(obj2);
                } finally {
                    ReferenceCountUtil.safeRelease(obj2);
                }
                obj2 = null;
            }
        }

        @Override
        final void doRequest(SubscriptionImpl subscription, long n) {
            int oldDemand = requested();
            if (oldDemand >= 2) {
                // Already have demand, so don't need to do anything, the current demand will complete the
                // stream.
                return;
            }
            setRequested(n >= 2 ? oldDemand + 2 : oldDemand + 1);
            doNotify(subscription);
        }

        @Override
        public final boolean isEmpty() {
            return false;
        }

        private void doNotify(SubscriptionImpl subscription) {
            if (inOnNext) {
                // Do not let Subscriber.onNext() reenter, because it can lead to weird-looking event ordering
                // for a Subscriber implemented like the following:
                //
                //   public void onNext(Object e) {
                //       subscription.request(1);
                //       ... Handle 'e' ...
                //   }
                //
                // Note that we do not call this method again, because we are already in the notification loop
                // and it will consume the element we've just added in addObjectOrEvent() from the queue as
                // expected.
                //
                // We do not need to worry about synchronizing the access to 'inOnNext' because the subscriber
                // methods must be on the same thread, or synchronized, according to Reactive Streams spec.
                return;
            }

            // Demand is always positive, so no need to check it.
            if (obj1 != null) {
                try {
                    doNotifyObject(subscription, obj1);
                } finally {
                    obj1 = null;
                }
            }

            if (requested() >= 2 && obj2 != null) {
                try {
                    doNotifyObject(subscription, obj2);
                } finally {
                    obj2 = null;
                }
                notifySubscriberOfCloseEvent(subscription, SUCCESSFUL_CLOSE);
            }
        }

        private void doNotifyObject(SubscriptionImpl subscription, T obj) {
            T published = prepareObjectForNotification(subscription, obj);
            inOnNext = true;
            try {
                subscription.subscriber().onNext(published);
            } finally {
                inOnNext = false;
            }
        }
    }

    public static class RegularFixedStreamMessage<T> extends AbstractFixedStreamMessage<T> {

        private final T[] objs;

        private int fulfilled;

        private boolean inOnNext;

        protected RegularFixedStreamMessage(T[] objs) {
            this.objs = Arrays.copyOf(objs, objs.length);
        }

        @Override
        final void cleanupObjects() {
            while (fulfilled < objs.length) {
                T obj = objs[fulfilled];
                objs[fulfilled++] = null;
                try {
                    onRemoval(obj);
                } finally {
                    ReferenceCountUtil.safeRelease(obj);
                }
            }
        }

        @Override
        final void doRequest(SubscriptionImpl subscription, long n) {
            final int oldDemand = requested();
            if (oldDemand >= objs.length) {
                // Already enough demand to finish the stream so don't need to do anything.
                return;
            }
            // As objs.length is fixed, we can safely cap the demand to it here.
            if (n >= objs.length) {
                setRequested(objs.length);
            } else {
                // As objs.length is an int, large demand will always fall into the above branch and there is no
                // chance of overflow, so just simply add the demand.
                setRequested((int) Math.min(oldDemand + n, objs.length));
            }
            if (requested() > oldDemand) {
                doNotify(subscription);
            }
        }

        private void doNotify(SubscriptionImpl subscription) {
            if (inOnNext) {
                // Do not let Subscriber.onNext() reenter, because it can lead to weird-looking event ordering
                // for a Subscriber implemented like the following:
                //
                //   public void onNext(Object e) {
                //       subscription.request(1);
                //       ... Handle 'e' ...
                //   }
                //
                // Note that we do not call this method again, because we are already in the notification loop
                // and it will consume the element we've just added in addObjectOrEvent() from the queue as
                // expected.
                //
                // We do not need to worry about synchronizing the access to 'inOnNext' because the subscriber
                // methods must be on the same thread, or synchronized, according to Reactive Streams spec.
                return;
            }

            final Subscriber<Object> subscriber = subscription.subscriber();
            for (;;) {
                if (closeEvent() != null) {
                    cleanup(subscription);
                    return;
                }

                if (fulfilled == objs.length) {
                    notifySubscriberOfCloseEvent(subscription, SUCCESSFUL_CLOSE);
                    return;
                }

                final int requested = requested();

                if (fulfilled == requested) {
                    break;
                }

                while (fulfilled < requested) {
                    if (closeEvent() != null) {
                        cleanup(subscription);
                        return;
                    }

                    T o = objs[fulfilled];
                    objs[fulfilled++] = null;
                    o = prepareObjectForNotification(subscription, o);
                    inOnNext = true;
                    try {
                        subscriber.onNext(o);
                    } finally {
                        inOnNext = false;
                    }
                }
            }
        }

        @Override
        public boolean isEmpty() {
            return false;
        }
    }

    private FixedStreamMessage() {}
}
