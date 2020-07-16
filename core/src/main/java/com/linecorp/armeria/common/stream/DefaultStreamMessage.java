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

import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static java.util.Objects.requireNonNull;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.jctools.queues.MpscChunkedArrayQueue;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A {@link StreamMessage} which buffers the elements to be signaled into a {@link Queue}.
 *
 * <p>This class implements the {@link StreamWriter} interface as well. A written element will be buffered
 * into the {@link Queue} until a {@link Subscriber} consumes it. Use {@link StreamWriter#whenConsumed()}
 * to control the rate of production so that the {@link Queue} does not grow up infinitely.
 *
 * <pre>{@code
 * void stream(DefaultStreamMessage<Integer> pub, int start, int end) {
 *     // Write 100 integers at most.
 *     int actualEnd = (int) Math.min(end, start + 100L);
 *     int i;
 *     for (i = start; i < actualEnd; i++) {
 *         pub.write(i);
 *     }
 *
 *     if (i == end) {
 *         // Wrote the last element.
 *         return;
 *     }
 *
 *     pub.whenConsumed().thenRun(() -> stream(pub, i, end));
 * }
 *
 * final DefaultStreamMessage<Integer> myPub = new DefaultStreamMessage<>();
 * stream(myPub, 0, Integer.MAX_VALUE);
 * }</pre>
 *
 * @param <T> the type of element signaled
 */
@UnstableApi
public class DefaultStreamMessage<T> extends AbstractStreamMessageAndWriter<T> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultStreamMessage.class);

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultStreamMessage, SubscriptionImpl>
            subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultStreamMessage.class, SubscriptionImpl.class, "subscription");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultStreamMessage, State> stateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultStreamMessage.class, State.class, "state");

    private final Queue<Object> queue;

    @Nullable
    @SuppressWarnings("unused")
    private volatile SubscriptionImpl subscription; // set only via subscriptionUpdater

    private long demand; // set only when in the subscriber thread

    @SuppressWarnings("FieldMayBeFinal")
    private volatile State state = State.OPEN;

    private volatile boolean wroteAny;

    private boolean inOnNext;
    private boolean invokedOnSubscribe;

    /**
     * Creates a new instance.
     */
    public DefaultStreamMessage() {
        queue = new MpscChunkedArrayQueue<>(32, 1 << 30);
    }

    @Override
    public boolean isOpen() {
        return state == State.OPEN;
    }

    @Override
    public boolean isEmpty() {
        return !isOpen() && !wroteAny;
    }

    @Override
    SubscriptionImpl subscribe(SubscriptionImpl subscription) {
        if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
            final SubscriptionImpl oldSubscription = this.subscription;
            assert oldSubscription != null;
            return oldSubscription;
        }

        final Subscriber<Object> subscriber = subscription.subscriber();
        if (subscription.needsDirectInvocation()) {
            subscribe(subscription, subscriber);
        } else {
            subscription.executor().execute(() -> {
                subscribe(subscription, subscriber);
            });
        }

        return subscription;
    }

    private void subscribe(SubscriptionImpl subscription, Subscriber<Object> subscriber) {
        try {
            invokedOnSubscribe = true;
            subscriber.onSubscribe(subscription);
        } catch (Throwable t) {
            if (setState(State.OPEN, State.CLEANUP) || setState(State.CLOSED, State.CLEANUP)) {
                notifySubscriberOfCloseEvent(subscription, newCloseEvent(t));
                throwIfFatal(t);
            } else {
                throwIfFatal(t);
                logger.warn("Subscriber.onSubscribe() should not raise an exception. subscriber: {}",
                            subscriber, t);
            }
        }
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
        SubscriptionImpl subscription = this.subscription;
        if (subscription == null) {
            final SubscriptionImpl newSubscription = new SubscriptionImpl(
                    this, AbortingSubscriber.get(cause), ImmediateEventExecutor.INSTANCE, false, false);
            if (subscriptionUpdater.compareAndSet(this, null, newSubscription)) {
                // We don't need to invoke onSubscribe() for AbortingSubscriber because it's just a placeholder.
                invokedOnSubscribe = true;
                subscription = newSubscription;
            } else {
                subscription = this.subscription;
            }
        }
        assert subscription != null;
        final SubscriptionImpl abortedSubscription = subscription;

        if (setState(State.OPEN, State.CLEANUP)) {
            notifySubscriberOfCloseEvent(abortedSubscription, newCloseEvent(cause));
            return;
        }

        if (setState(State.CLOSED, State.CLEANUP)) {
            // close() or close(cause) has been called before cancel() or abort() is called.
            if (abortedSubscription.needsDirectInvocation()) {
                abort0(cause, abortedSubscription);
            } else {
                abortedSubscription.executor().execute(() -> abort0(cause, abortedSubscription));
            }
        }
    }

    private void abort0(Throwable cause, SubscriptionImpl subscription) {
        final Object o = queue.peek();
        // If there's no data pushed (i.e empty stream), notify subscriber with the event pushed by
        // close() or close(cause).
        if (!wroteAny && o instanceof CloseEvent) {
            notifySubscriberOfCloseEvent(subscription, (CloseEvent) queue.remove());
            return;
        }

        notifySubscriberOfCloseEvent(subscription, newCloseEvent(cause));
    }

    @Override
    void addObject(T obj) {
        wroteAny = true;
        addObjectOrEvent(obj);
    }

    @Override
    long demand() {
        return demand;
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
        final long oldDemand = demand;
        if (oldDemand >= Long.MAX_VALUE - n) {
            demand = Long.MAX_VALUE;
        } else {
            demand = oldDemand + n;
        }

        if (oldDemand == 0 && !queue.isEmpty()) {
            notifySubscriber0();
        }
    }

    @Override
    void cancel() {
        if (setState(State.OPEN, State.CLEANUP) || setState(State.CLOSED, State.CLEANUP)) {
            // It the state was CLOSED, close() or close(cause) has been called before cancel() or abort()
            // is called. We just ignore the previously pushed event and deal with CANCELLED_CLOSE.
            final SubscriptionImpl subscription = this.subscription;
            assert subscription != null;
            notifySubscriberOfCloseEvent(subscription, CANCELLED_CLOSE);
        }
    }

    private void notifySubscriberOfCloseEvent(SubscriptionImpl subscription, CloseEvent event) {
        if (subscription.needsDirectInvocation()) {
            notifySubscriberOfCloseEvent0(subscription, event);
        } else {
            subscription.executor().execute(() -> notifySubscriberOfCloseEvent0(subscription, event));
        }
    }

    private void notifySubscriberOfCloseEvent0(SubscriptionImpl subscription, CloseEvent event) {
        try {
            event.notifySubscriber(subscription, whenComplete());
        } finally {
            subscription.clearSubscriber();
            Throwable cause = event.cause;
            for (;;) {
                final Object e = queue.poll();
                if (e == null) {
                    break;
                }

                // We already notified to the subscriber so skip.
                if (e instanceof CloseEvent) {
                    continue;
                }

                if (e instanceof CompletableFuture) {
                    if (cause == null) {
                        cause = ClosedStreamException.get();
                    }
                    ((CompletableFuture<?>) e).completeExceptionally(cause);
                    continue;
                }

                try {
                    @SuppressWarnings("unchecked")
                    final T obj = (T) e;
                    onRemoval(obj);
                } finally {
                    PooledObjects.close(e);
                }
            }
        }
    }

    @Override
    void addObjectOrEvent(Object obj) {
        queue.add(obj);
        notifySubscriber();
    }

    final void notifySubscriber() {
        final SubscriptionImpl subscription = this.subscription;
        if (subscription == null) {
            return;
        }

        if (queue.isEmpty()) {
            return;
        }

        if (subscription.needsDirectInvocation()) {
            notifySubscriber0();
        } else {
            subscription.executor().execute(this::notifySubscriber0);
        }
    }

    private void notifySubscriber0() {
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

        final SubscriptionImpl subscription = this.subscription;
        if (!invokedOnSubscribe) {
            final Executor executor = subscription.executor();

            // Subscriber.onSubscribe() was not invoked yet.
            // Reschedule the notification so that onSubscribe() is invoked before other events.
            //
            // Note:
            // The rescheduling will occur at most once because the invocation of onSubscribe() must have been
            // scheduled already by subscribe(), given that this.subscription is not null at this point and
            // subscribe() is the only place that sets this.subscription.

            executor.execute(this::notifySubscriber0);
            return;
        }

        for (;;) {
            if (state == State.CLEANUP) {
                cleanupObjects();
                return;
            }

            final Object o = queue.peek();
            if (o == null) {
                break;
            }

            if (o instanceof CloseEvent) {
                handleCloseEvent(subscription, (CloseEvent) queue.remove());
                break;
            }

            if (o instanceof AwaitDemandFuture) {
                if (notifyAwaitDemandFuture()) {
                    // Notified successfully.
                    continue;
                } else {
                    // Not enough demand.
                    break;
                }
            }

            if (!notifySubscriberWithElements(subscription)) {
                // Not enough demand.
                break;
            }
        }
    }

    private boolean notifySubscriberWithElements(SubscriptionImpl subscription) {
        final Subscriber<Object> subscriber = subscription.subscriber();
        if (demand == 0) {
            return false;
        }

        if (demand != Long.MAX_VALUE) {
            demand--;
        }

        @SuppressWarnings("unchecked")
        T o = (T) queue.remove();
        inOnNext = true;
        try {
            o = prepareObjectForNotification(subscription, o);
            subscriber.onNext(o);
        } catch (Throwable t) {
            if (setState(State.OPEN, State.CLEANUP) || setState(State.CLOSED, State.CLEANUP)) {
                notifySubscriberOfCloseEvent(subscription, newCloseEvent(t));
                throwIfFatal(t);
            } else {
                throwIfFatal(t);
                logger.warn("Subscriber.onNext({}) should not raise an exception. subscriber: {}",
                            o, subscriber, t);
            }

            return false;
        } finally {
            inOnNext = false;
        }
        return true;
    }

    private boolean notifyAwaitDemandFuture() {
        if (demand == 0) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final CompletableFuture<Void> f = (CompletableFuture<Void>) queue.remove();
        f.complete(null);

        return true;
    }

    private void handleCloseEvent(SubscriptionImpl subscription, CloseEvent o) {
        if (setState(State.CLOSED, State.CLEANUP)) {
            notifySubscriberOfCloseEvent(subscription, o);
        }
    }

    @Override
    public void close() {
        if (setState(State.OPEN, State.CLOSED)) {
            addObjectOrEvent(SUCCESSFUL_CLOSE);
        }
    }

    @Override
    public void close(Throwable cause) {
        requireNonNull(cause, "cause");
        if (cause instanceof CancelledSubscriptionException) {
            throw new IllegalArgumentException("cause: " + cause + " (must use Subscription.cancel())");
        }

        tryClose(cause);
    }

    /**
     * Tries to close the stream with the specified {@code cause}.
     *
     * @return {@code true} if the stream has been closed by this method call.
     *         {@code false} if the stream has been closed already by other party.
     */
    protected final boolean tryClose(Throwable cause) {
        if (setState(State.OPEN, State.CLOSED)) {
            addObjectOrEvent(new CloseEvent(cause));
            return true;
        }
        return false;
    }

    private boolean setState(State oldState, State newState) {
        assert newState != State.OPEN : "oldState: " + oldState + ", newState: " + newState;
        return stateUpdater.compareAndSet(this, oldState, newState);
    }

    private void cleanupObjects() {
        Throwable cause = null;
        for (;;) {
            final Object e = queue.poll();
            if (e == null) {
                break;
            }

            if (e instanceof CloseEvent) {
                continue;
            }

            if (e instanceof CompletableFuture) {
                if (cause == null) {
                    cause = ClosedStreamException.get();
                }
                ((CompletableFuture<?>) e).completeExceptionally(cause);
                continue;
            }

            try {
                @SuppressWarnings("unchecked")
                final T obj = (T) e;
                onRemoval(obj);
            } finally {
                PooledObjects.close(e);
            }
        }
    }
}
