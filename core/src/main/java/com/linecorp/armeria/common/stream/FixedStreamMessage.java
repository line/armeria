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

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.Flags;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A {@link StreamMessage} used when all the objects that will be published are known at construction time.
 * Reduced synchronization and allocation allow for much higher performance than {@link DefaultStreamMessage},
 * so this class should generally be used when the objects are known.
 */
public class FixedStreamMessage<T> extends AbstractStreamMessage<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<FixedStreamMessage, SubscriptionImpl>
            subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
            FixedStreamMessage.class, SubscriptionImpl.class, "subscription");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<FixedStreamMessage, CloseEvent>
            closeEventUpdater = AtomicReferenceFieldUpdater.newUpdater(
            FixedStreamMessage.class, CloseEvent.class, "closeEvent");

    /**
     * Creates a new {@link FixedStreamMessage} that will publish the given {@code objs}. {@code objs} is not
     * copied so must not be mutated after this method call (it is generally meant to be used with a varargs
     * invocation).
     */
    @SafeVarargs
    public static <T> FixedStreamMessage<T> of(T... objs) {
        requireNonNull(objs, "objs");
        return new FixedStreamMessage<>(objs);
    }

    private final T[] objs;

    @SuppressWarnings("unused")
    private volatile SubscriptionImpl subscription; // set only via subscriptionUpdater

    private volatile CloseEvent closeEvent;

    private int requested;
    private int fulfilled;

    private boolean inOnNext;
    private boolean invokedOnSubscribe;

    /**
     * Initializes a {@link FixedStreamMessage} that will publish the given {@code objs}.
     */
    protected FixedStreamMessage(T[] objs) {
        this.objs = requireNonNull(objs, "objs");
    }

    @Override
    public boolean isOpen() {
        // Fixed streams are closed on construction.
        return false;
    }

    @Override
    public boolean isEmpty() {
        return objs.length == 0;
    }

    @Override
    public void abort() {
        final SubscriptionImpl currentSubscription = subscription;
        if (currentSubscription != null) {
            cancelOrAbort(false);
            return;
        }

        final SubscriptionImpl newSubscription = new SubscriptionImpl(
                this, AbortingSubscriber.get(), ImmediateEventExecutor.INSTANCE, false);
        if (subscriptionUpdater.compareAndSet(this, null, newSubscription)) {
            // We don't need to invoke onSubscribe() for AbortingSubscriber because it's just a placeholder.
            invokedOnSubscribe = true;
        }
        cancelOrAbort(false);
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
            invokedOnSubscribe = true;
            subscriber.onSubscribe(subscription);
        } else {
            executor.execute(() -> {
                invokedOnSubscribe = true;
                subscriber.onSubscribe(subscription);
            });
        }
    }

    @Override
    long demand() {
        return requested;
    }

    @Override
    void request(long n) {
        final SubscriptionImpl subscription = this.subscription;
        // A user cannot access subscription without subscribing.
        assert subscription != null;

        if (subscription.needsDirectInvocation()) {
            doRequest(n);
        } else {
            subscription.executor().execute(() -> doRequest(n));
        }
    }

    private void doRequest(long n) {
        final long oldDemand = requested;
        // If this is an empty stream, any demand is enough to complete it. We special case it to allow other
        // assumptions on size to work correctly for the non-empty case.
        if (isEmpty() && oldDemand == 0) {
            requested = 1;
            notifySubscriber();
            return;
        }
        if (oldDemand >= objs.length) {
            // Already enough demand to finish the stream so don't need to do anything.
            return;
        }
        // As objs.length is fixed, we can safely cap the demand to it here.
        if (n >= objs.length) {
            requested = objs.length;
        } else {
            // As objs.length is an int, large demand will always fall into the above branch and there is no
            // chance of overflow, so just simply add the demand.
            requested = (int) Math.min(oldDemand + n, objs.length);
        }
        if (requested > oldDemand) {
            notifySubscriber();
        }
    }

    @Override
    void cancel() {
        cancelOrAbort(true);
    }

    @Override
    void notifySubscriberOfCloseEvent(SubscriptionImpl subscription, CloseEvent event) {
        try {
            event.notifySubscriber(subscription, completionFuture());
        } finally {
            subscription.clearSubscriber();
            cleanup();
        }
    }

    private void notifySubscriber() {
        final SubscriptionImpl subscription = this.subscription;
        if (subscription == null) {
            return;
        }

        if (fulfilled == requested) {
            return;
        }

        if (subscription.needsDirectInvocation()) {
            notifySubscriber0(subscription);
        } else {
            subscription.executor().execute(() -> notifySubscriber0(subscription));
        }
    }

    private void notifySubscriber0(SubscriptionImpl subscription) {
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

        if (!invokedOnSubscribe) {
            // Subscriber.onSubscribe() was not invoked yet.
            // Reschedule the notification so that onSubscribe() is invoked before other events.
            //
            // Note:
            // The rescheduling will occur at most once because the invocation of onSubscribe() must have been
            // scheduled already by subscribe(), given that this.subscription is not null at this point and
            // subscribe() is the only place that sets this.subscription.

            subscription.executor().execute(() -> this.notifySubscriber0(subscription));
            return;
        }

        final Subscriber<Object> subscriber = subscription.subscriber();
        for (;;) {
            if (closeEvent != null) {
                cleanup();
                return;
            }

            if (fulfilled == objs.length) {
                notifySubscriberOfCloseEvent(subscription, SUCCESSFUL_CLOSE);
                return;
            }

            final long requested = this.requested;

            if (fulfilled == requested) {
                break;
            }

            while (fulfilled < requested) {
                if (closeEvent != null) {
                    cleanup();
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
                cleanup();
            } else {
                subscription.executor().execute(this::cleanup);
            }
        }
    }

    private void cleanup() {
        final CloseEvent closeEvent = this.closeEvent;
        this.closeEvent = null;
        if (closeEvent != null) {
            notifySubscriberOfCloseEvent(subscription, closeEvent);
            // Close event will cleanup.
            return;
        }
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
}
