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

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

/**
 * A {@link StreamMessage} which buffers the elements to be signaled into a {@link Queue}.
 *
 * <p>This class implements the {@link StreamWriter} interface as well. A written element will be buffered
 * into the {@link Queue} until a {@link Subscriber} consumes it. Use {@link StreamWriter#onDemand(Runnable)}
 * to control the rate of production so that the {@link Queue} does not grow up infinitely.
 *
 * <pre>{@code
 * void stream(QueueBasedPublished<Integer> pub, int start, int end) {
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
 *     pub.onDemand(() -> stream(pub, i, end));
 * }
 *
 * final QueueBasedPublisher<Integer> myPub = new QueueBasedPublisher<>();
 * stream(myPub, 0, Integer.MAX_VALUE);
 * }</pre>
 *
 * @param <T> the type of element signaled
 */
public class DefaultStreamMessage<T> implements StreamMessage<T>, StreamWriter<T> {

    private enum State {
        /**
         * The initial state. Will enter {@link #CLOSED} or {@link #CLEANUP}.
         */
        OPEN,
        /**
         * {@link #close()} or {@link #close(Throwable)} has been called. Will enter {@link #CLEANUP} after
         * {@link Subscriber#onComplete()} or {@link Subscriber#onError(Throwable)} is invoked.
         */
        CLOSED,
        /**
         * Anything in the queue must be cleaned up.
         * Enters this state when there's no chance of consumption by subscriber.
         * i.e. when any of the following methods are invoked:
         * <ul>
         *   <li>{@link Subscription#cancel()}</li>
         *   <li>{@link #abort()} (via {@link AbortingSubscriber})</li>
         *   <li>{@link Subscriber#onComplete()}</li>
         *   <li>{@link Subscriber#onError(Throwable)}</li>
         * </ul>
         */
        CLEANUP
    }

    private static final CloseEvent SUCCESSFUL_CLOSE = new CloseEvent(null);
    private static final CloseEvent CANCELLED_CLOSE = new CloseEvent(
            Exceptions.clearTrace(CancelledSubscriptionException.get()));
    private static final CloseEvent ABORTED_CLOSE = new CloseEvent(
            Exceptions.clearTrace(AbortedStreamException.get()));

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultStreamMessage, SubscriptionImpl>
            subscriptionUpdater = AtomicReferenceFieldUpdater.newUpdater(
                    DefaultStreamMessage.class, SubscriptionImpl.class, "subscription");

    @SuppressWarnings("rawtypes")
    private static final AtomicLongFieldUpdater<DefaultStreamMessage> demandUpdater =
            AtomicLongFieldUpdater.newUpdater(DefaultStreamMessage.class, "demand");

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultStreamMessage, State> stateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultStreamMessage.class, State.class, "state");

    private final Queue<Object> queue;
    private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

    @SuppressWarnings("unused")
    private volatile SubscriptionImpl subscription; // set only via subscriptionUpdater

    @SuppressWarnings("unused")
    private volatile long demand; // set only via demandUpdater

    @SuppressWarnings("FieldMayBeFinal")
    private volatile State state = State.OPEN;

    private volatile boolean wroteAny;

    private boolean inOnNext;

    /**
     * Creates a new instance with a new {@link ConcurrentLinkedQueue}.
     */
    public DefaultStreamMessage() {
        this(new ConcurrentLinkedQueue<>());
    }

    /**
     * Creates a new instance with the specified {@link Queue}.
     */
    public DefaultStreamMessage(Queue<Object> queue) {
        this.queue = requireNonNull(queue, "queue");
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
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        subscribe(subscriber, false);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, boolean withPooledObjects) {
        requireNonNull(subscriber, "subscriber");
        subscribe(new SubscriptionImpl(this, subscriber, null, withPooledObjects));
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, Executor executor) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        subscribe(subscriber, executor, false);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, Executor executor, boolean withPooledObjects) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        subscribe(new SubscriptionImpl(this, subscriber, executor, withPooledObjects));
    }

    private void subscribe(SubscriptionImpl subscription) {
        final Subscriber<Object> subscriber = subscription.subscriber();
        final Executor executor = subscription.executor();

        if (!subscriptionUpdater.compareAndSet(this, null, subscription)) {
            failLateSubscriber(this.subscription, subscriber);
            return;
        }

        if (subscription.needsDirectInvocation()) {
            subscription.invokeOnSubscribe();
        } else {
            executor.execute(subscription::invokeOnSubscribe);
        }
    }

    private static void failLateSubscriber(SubscriptionImpl subscription, Subscriber<?> lateSubscriber) {
        final Subscriber<?> oldSubscriber = subscription.subscriber;
        final Throwable cause;
        if (oldSubscriber instanceof AbortingSubscriber) {
            cause = AbortedStreamException.get();
        } else {
            cause = new IllegalStateException("subscribed by other subscriber already");
        }

        if (subscription.needsDirectInvocation()) {
            lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
            lateSubscriber.onError(cause);
        } else {
            subscription.executor().execute(() -> {
                lateSubscriber.onSubscribe(NoopSubscription.INSTANCE);
                lateSubscriber.onError(cause);
            });
        }
    }

    @Override
    public void abort() {
        final SubscriptionImpl currentSubscription = subscription;
        if (currentSubscription != null) {
            currentSubscription.abort();
            return;
        }

        final SubscriptionImpl newSubscription = new SubscriptionImpl(
                this, AbortingSubscriber.get(), null, false);
        if (subscriptionUpdater.compareAndSet(this, null, newSubscription)) {
            // We don't need to invoke onSubscribe() for AbortingSubscriber because it's just a placeholder.
            newSubscription.invokedOnSubscribe = true;
            newSubscription.abort();
        } else {
            subscription.abort();
        }
    }

    @Override
    public boolean write(T obj) {
        requireNonNull(obj, "obj");
        if (obj instanceof ReferenceCounted) {
            ((ReferenceCounted) obj).touch();
            if (!(obj instanceof ByteBufHolder) && !(obj instanceof ByteBuf)) {
                throw new IllegalArgumentException(
                        "can't publish a ReferenceCounted that's not a ByteBuf or a ByteBufHolder: " + obj);
            }
        }

        if (!isOpen()) {
            ReferenceCountUtil.safeRelease(obj);
            return false;
        }

        wroteAny = true;
        pushObject(obj);
        return true;
    }

    @Override
    public boolean write(Supplier<? extends T> supplier) {
        return write(supplier.get());
    }

    @Override
    public CompletableFuture<Void> onDemand(Runnable task) {
        requireNonNull(task, "task");

        final AwaitDemandFuture f = new AwaitDemandFuture();
        if (!isOpen()) {
            f.completeExceptionally(ClosedPublisherException.get());
            return f;
        }

        pushObject(f);
        return f.thenRun(task);
    }

    private void pushObject(Object obj) {
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
            // and it will consume the element we've just added in pushObject() from the queue as expected.
            //
            // We do not need to worry about synchronizing the access to 'inOnNext' because the subscriber
            // methods must be on the same thread, or synchronized, according to Reactive Streams spec.
            return;
        }

        final SubscriptionImpl subscription = this.subscription;
        if (!subscription.invokedOnSubscribe) {
            Executor executor = subscription.executor();
            if (executor == null) {
                executor = ForkJoinPool.commonPool();
            }

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
                cleanup();
                return;
            }

            final Object o = queue.peek();
            if (o == null) {
                break;
            }

            if (o instanceof CloseEvent) {
                notifySubscriberWithCloseEvent(subscription, (CloseEvent) o);
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
        for (;;) {
            final long demand = this.demand;
            if (demand == 0) {
                break;
            }

            if (demand == Long.MAX_VALUE || demandUpdater.compareAndSet(this, demand, demand - 1)) {
                @SuppressWarnings("unchecked")
                T o = (T) queue.remove();
                ReferenceCountUtil.touch(o);
                onRemoval(o);
                if (!subscription.withPooledObjects()) {
                    if (o instanceof ByteBufHolder) {
                        o = copyAndRelease((ByteBufHolder) o);
                    } else if (o instanceof ByteBuf) {
                        o = copyAndRelease((ByteBuf) o);
                    }
                }

                inOnNext = true;
                subscriber.onNext(o);
                inOnNext = false;
                return true;
            }
        }

        return false;
    }

    private T copyAndRelease(ByteBufHolder o) {
        try {
            final ByteBuf content = Unpooled.wrappedBuffer(ByteBufUtil.getBytes(o.content()));
            @SuppressWarnings("unchecked")
            final T copy = (T) o.replace(content);
            return copy;
        } finally {
            ReferenceCountUtil.safeRelease(o);
        }
    }

    private T copyAndRelease(ByteBuf o) {
        try {
            @SuppressWarnings("unchecked")
            final T copy = (T) Unpooled.copiedBuffer(o);
            return copy;
        } finally {
            ReferenceCountUtil.safeRelease(o);
        }
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

    private void notifySubscriberWithCloseEvent(SubscriptionImpl subscription, CloseEvent o) {
        setState(State.OPEN, State.CLEANUP);
        try {
            o.notifySubscriber(subscription, completionFuture);
        } finally {
            cleanup();
        }
    }

    /**
     * Invoked after an element is removed from the {@link Queue} and before {@link Subscriber#onNext(Object)}
     * is invoked.
     *
     * @param obj the removed element
     */
    protected void onRemoval(T obj) {}

    @Override
    public final CompletableFuture<Void> completionFuture() {
        return completionFuture;
    }

    @Override
    public void close() {
        if (setState(State.OPEN, State.CLOSED)) {
            pushObject(SUCCESSFUL_CLOSE);
        }
    }

    @Override
    public void close(Throwable cause) {
        requireNonNull(cause, "cause");
        if (cause instanceof CancelledSubscriptionException) {
            throw new IllegalArgumentException("cause: " + cause + " (must use Subscription.cancel())");
        }

        if (setState(State.OPEN, State.CLOSED)) {
            pushObject(new CloseEvent(cause));
        }
    }

    private boolean setState(State oldState, State newState) {
        assert newState != State.OPEN : "oldState: " + oldState + ", newState: " + newState;
        return stateUpdater.compareAndSet(this, oldState, newState);
    }

    private void cleanup() {
        final Throwable cause = ClosedPublisherException.get();
        for (;;) {
            final Object e = queue.poll();
            if (e == null) {
                break;
            }

            try {
                if (e instanceof CloseEvent) {
                    ((CloseEvent) e).notifySubscriber(subscription, completionFuture);
                    subscription.clearSubscriber();
                    continue;
                }

                if (e instanceof CompletableFuture) {
                    ((CompletableFuture<?>) e).completeExceptionally(cause);
                }

                @SuppressWarnings("unchecked")
                T obj = (T) e;
                onRemoval(obj);
            } finally {
                ReferenceCountUtil.safeRelease(e);
            }
        }
    }

    private static final class SubscriptionImpl implements Subscription {

        private final DefaultStreamMessage<?> publisher;
        private Subscriber<Object> subscriber;
        private final Executor executor;
        private final boolean withPooledObjects;
        private boolean invokedOnSubscribe;
        private volatile boolean cancelRequested;

        @SuppressWarnings("unchecked")
        SubscriptionImpl(DefaultStreamMessage<?> publisher, Subscriber<?> subscriber, Executor executor,
                         boolean withPooledObjects) {
            this.publisher = publisher;
            this.subscriber = (Subscriber<Object>) subscriber;
            this.executor = executor;
            this.withPooledObjects = withPooledObjects;
        }

        Subscriber<Object> subscriber() {
            return subscriber;
        }

        /**
         * Replaces the subscriber with a placeholder so that it can be garbage-collected and
         * we conform to the Reactive Streams specification rule 3.13.
         */
        void clearSubscriber() {
            if (!(subscriber instanceof AbortingSubscriber)) {
                subscriber = NeverInvokedSubscriber.get();
            }
        }

        Executor executor() {
            return executor;
        }

        boolean withPooledObjects() {
            return withPooledObjects;
        }

        boolean cancelRequested() {
            return cancelRequested;
        }

        void invokeOnSubscribe() {
            invokedOnSubscribe = true;
            subscriber().onSubscribe(this);
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                invokeOnError(new IllegalArgumentException(
                        "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
                return;
            }

            for (;;) {
                final long oldDemand = publisher.demand;
                final long newDemand;
                if (oldDemand >= Long.MAX_VALUE - n) {
                    newDemand = Long.MAX_VALUE;
                } else {
                    newDemand = oldDemand + n;
                }

                if (demandUpdater.compareAndSet(publisher, oldDemand, newDemand)) {
                    if (oldDemand == 0) {
                        publisher.notifySubscriber();
                    }
                    break;
                }
            }
        }

        void abort() {
            cancelOrAbort(false);
        }

        @Override
        public void cancel() {
            cancelOrAbort(true);
        }

        private void cancelOrAbort(boolean cancel) {
            if (publisher.setState(State.OPEN, State.CLEANUP)) {
                final CloseEvent closeEvent;
                if (cancel) {
                    closeEvent = Flags.verboseExceptions() ?
                                 new CloseEvent(CancelledSubscriptionException.get()) : CANCELLED_CLOSE;
                } else {
                    closeEvent = Flags.verboseExceptions() ?
                                 new CloseEvent(AbortedStreamException.get()) : ABORTED_CLOSE;
                }
                publisher.pushObject(closeEvent);
                return;
            }

            cancelRequested = cancel;

            switch (publisher.state) {
                case CLOSED:
                    // close() has been called before cancel(). There's no need to push a CloseEvent,
                    // but we need to ensure the completionFuture is notified and any pending objects
                    // are removed.
                    if (publisher.setState(State.CLOSED, State.CLEANUP)) {
                        final Executor executor = executor();
                        // TODO(anuraag): Consider pushing a cleanup event instead of serializing the activity
                        // through the event loop.
                        if (executor != null) {
                            executor.execute(publisher::cleanup);
                        } else {
                            publisher.cleanup();
                        }
                    } else {
                        // Other thread set the state to CLEANUP already and will call cleanup().
                    }
                    break;
                case CLEANUP:
                    // Cleaned up already.
                    break;
                default: // OPEN: should never reach here.
                    throw new Error();
            }
        }

        private void invokeOnError(Throwable cause) {
            if (needsDirectInvocation()) {
                subscriber.onError(cause);
            } else {
                executor.execute(() -> subscriber.onError(cause));
            }
        }

        // We directly run callbacks for event loops if we're already on the loop, which applies to the vast
        // majority of cases.
        boolean needsDirectInvocation() {
            return executor == null ||
                   executor instanceof EventLoop && ((EventLoop) executor).inEventLoop();
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(Subscription.class)
                              .add("publisher", publisher)
                              .add("demand", publisher.demand)
                              .add("executor", executor).toString();
        }
    }

    private static final class AwaitDemandFuture extends CompletableFuture<Void> {}

    private static final class CloseEvent {
        private final Throwable cause;

        CloseEvent(Throwable cause) {
            this.cause = cause;
        }

        void notifySubscriber(SubscriptionImpl subscription, CompletableFuture<?> completionFuture) {
            if (completionFuture.isDone()) {
                // Notified already
                return;
            }

            final Subscriber<Object> subscriber = subscription.subscriber();
            Throwable cause = this.cause;
            if (cause == null && subscription.cancelRequested()) {
                cause = CancelledSubscriptionException.get();
            }

            if (cause == null) {
                try {
                    subscriber.onComplete();
                } finally {
                    completionFuture.complete(null);
                }
            } else {
                try {
                    if (!(cause instanceof CancelledSubscriptionException)) {
                        subscriber.onError(cause);
                    }
                } finally {
                    completionFuture.completeExceptionally(cause);
                }
            }
        }

        @Override
        public String toString() {
            if (cause == null) {
                return "CloseEvent";
            } else {
                return "CloseEvent(" + cause + ')';
            }
        }
    }
}
