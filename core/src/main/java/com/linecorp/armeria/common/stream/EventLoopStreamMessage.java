/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.RequestContext;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A {@link StreamMessage} optimized for when writes and reads all happen on the provided {@link EventLoop},
 * removing the need for most synchronization from the hot path. This should satisfy standard cases when using
 * Armeria. It is not required for writes or reads to use the provided {@link EventLoop} but in such a case, it
 * will be significantly faster to use {@link DefaultStreamMessage}.
 *
 * <p>Note that when {@link Subscription#cancel()} or {@link #abort()} are called from a different thread, the
 * stream will continue to signal objects until demand is satisfied, rather than stopping in the middle. If this
 * is an issue, it is recommended to use {@link DefaultStreamMessage}.
 */
// NB: Methods in this class prefixed with 'do' must be run on the stream's event loop.
public class EventLoopStreamMessage<T> extends AbstractStreamMessageAndWriter<T> {

    private static final ConcurrentHashMap<List<StackTraceElement>, Boolean>
            UNEXPECTED_EVENT_LOOP_STACK_TRACES = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<EventLoopStreamMessage> abortedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(EventLoopStreamMessage.class, "aborted");
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<EventLoopStreamMessage> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(EventLoopStreamMessage.class, "subscribed");

    private static final Logger logger = LoggerFactory.getLogger(EventLoopStreamMessage.class);

    private final EventLoop eventLoop;
    private final Queue<Object> queue;

    @Nullable
    private SubscriptionImpl subscription;
    private long demand;
    private boolean invokedOnSubscribe;
    private boolean inOnNext;

    private State state = State.OPEN;

    @SuppressWarnings("unused")
    private volatile int subscribed;  // set only via subscribedUpdater
    @SuppressWarnings("unused")
    private volatile int aborted;  // set only via abortedUpdater
    private volatile boolean isOpen = true;
    private volatile boolean wroteAny;

    /**
     * Creates a new {@link EventLoopStreamMessage} which executes all writes on an arbitrary {@link EventLoop}.
     * It is highly recommended to use {@link #EventLoopStreamMessage(EventLoop)} instead to allow writes to
     * happen on the same {@link EventLoop} as this stream's.
     */
    public EventLoopStreamMessage() {
        this(RequestContext.mapCurrent(RequestContext::eventLoop, () -> {
            final UnexpectedEventLoopException e = new UnexpectedEventLoopException();
            final List<StackTraceElement> stackTrace = ImmutableList.copyOf(e.getStackTrace());
            UNEXPECTED_EVENT_LOOP_STACK_TRACES.computeIfAbsent(stackTrace, unused -> {
                logger.warn("Creating EventLoopStreamMessage without specifying EventLoop. " +
                            "This will be very slow if writer or subscriber run in a different EventLoop.", e);
                return true;
            });
            return CommonPools.workerGroup().next();
        }));
    }

    /**
     * Creates a new {@link EventLoopStreamMessage} which executes all writes on the provided {@link EventLoop}.
     */
    public EventLoopStreamMessage(EventLoop eventLoop) {
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        queue = new ArrayDeque<>();
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public boolean isEmpty() {
        return !isOpen() && !wroteAny;
    }

    @Override
    protected EventExecutor defaultSubscriberExecutor() {
        return eventLoop;
    }

    @Override
    void subscribe(SubscriptionImpl subscription) {
        final Subscriber<?> subscriber = subscription.subscriber();
        if (!subscribedUpdater.compareAndSet(this, 0, 1)) {
            eventLoop.execute(() -> failLateSubscriber(this.subscription, subscriber));
            return;
        }

        if (eventLoop.inEventLoop()) {
            doSubscribe(subscription);
        } else {
            eventLoop.execute(() -> doSubscribe(subscription));
        }
    }

    @Override
    public void close() {
        if (eventLoop.inEventLoop()) {
            doClose(null);
        } else {
            eventLoop.execute(() -> doClose(null));
        }
    }

    @Override
    public void close(Throwable cause) {
        requireNonNull(cause, "cause");
        if (cause instanceof CancelledSubscriptionException) {
            throw new IllegalArgumentException("cause: " + cause + " (must use Subscription.cancel())");
        }

        if (eventLoop.inEventLoop()) {
            doClose(cause);
        } else {
            eventLoop.execute(() -> doClose(cause));
        }
    }

    @Override
    public void abort() {
        if (abortedUpdater.compareAndSet(this, 0, 1)) {
            // Let readers of isOpen know immediately that the stream was aborted.
            isOpen = false;

            if (subscribedUpdater.compareAndSet(this, 0, 1)) {
                if (eventLoop.inEventLoop()) {
                    doSetAbortedSubscription();
                    doCancelOrAbort(false);
                } else {
                    eventLoop.execute(() -> {
                        doSetAbortedSubscription();
                        doCancelOrAbort(false);
                    });
                }
                return;
            }

            cancelOrAbort(false);
        }
    }

    @Override
    long demand() {
        return demand;
    }

    @Override
    void request(long n) {
        if (eventLoop.inEventLoop()) {
            doRequest(n);
        } else {
            eventLoop.execute(() -> doRequest(n));
        }
    }

    @Override
    void cancel() {
        cancelOrAbort(true);
    }

    @Override
    void notifySubscriberOfCloseEvent(SubscriptionImpl subscription, CloseEvent event) {
        if (subscription.needsDirectInvocation()) {
            try {
                event.notifySubscriber(subscription, completionFuture());
            } finally {
                subscription.clearSubscriber();
                cleanup();
            }
        } else {
            subscription.executor().execute(() -> {
                try {
                    event.notifySubscriber(subscription, completionFuture());
                } finally {
                    subscription.clearSubscriber();
                    eventLoop.execute(this::cleanup);
                }
            });
        }
    }

    @Override
    void addObject(T obj) {
        wroteAny = true;
        if (eventLoop.inEventLoop()) {
            doAddObject(obj);
        } else {
            eventLoop.execute(() -> doAddObject(obj));
        }
    }

    @Override
    void addObjectOrEvent(Object obj) {
        if (eventLoop.inEventLoop()) {
            doAddObjectOrEvent(obj);
        } else {
            eventLoop.execute(() -> doAddObjectOrEvent(obj));
        }
    }

    private void doClose(@Nullable Throwable cause) {
        if (state != State.OPEN) {
            return;
        }
        doSetState(State.CLOSED);
        final CloseEvent event = cause == null ? SUCCESSFUL_CLOSE : new CloseEvent(cause);
        doAddObjectOrEvent(event);
    }

    private void doSetState(State state) {
        this.state = state;
        isOpen = false;
    }

    private void doSubscribe(SubscriptionImpl subscription) {
        this.subscription = subscription;

        if (subscription.needsDirectInvocation()) {
            invokedOnSubscribe = true;
            subscription.subscriber().onSubscribe(subscription);
        } else {
            subscription.executor().execute(() -> {
                subscription.subscriber().onSubscribe(subscription);
                eventLoop.execute(() -> invokedOnSubscribe = true);
            });
        }
    }

    private void doRequest(long n) {
        final long oldDemand = demand;
        if (oldDemand >= Long.MAX_VALUE - n) {
            demand = Long.MAX_VALUE;
        } else {
            demand = oldDemand + n;
        }

        if (oldDemand == 0) {
            doNotifySubscriberIfNotEmpty();
        }
    }

    private void doAddObject(T obj) {
        if (queue.isEmpty() && demand > 0 && !inOnNext) {
            final SubscriptionImpl subscription = this.subscription;
            // Nothing in the queue and the subscriber is ready for an object, so send it directly.
            demand--;
            doNotifySubscriberOfObject(subscription, obj);
            return;
        }

        doAddObjectOrEvent(obj);
    }

    private void doAddObjectOrEvent(Object obj) {
        queue.add(obj);

        if (subscription != null) {
            doNotifySubscriber(subscription);
        }
    }

    private void doNotifySubscriberIfNotEmpty() {
        final SubscriptionImpl subscription = this.subscription;
        if (subscription == null) {
            return;
        }

        if (queue.isEmpty()) {
            return;
        }

        doNotifySubscriber(subscription);
    }

    private void doNotifySubscriber(SubscriptionImpl subscription) {
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
            // and it will consume the element we've just added in addObject() from the queue as expected.
            //
            // We do not need to worry about synchronizing the access to 'inOnNext' because the subscriber
            // methods must be on the same thread, or synchronized, according to Reactive Streams spec.
            return;
        }

        if (!invokedOnSubscribe) {
            // Subscriber.onSubscribe() was not invoked yet.
            // Reschedule the notification so that onSubscribe() is invoked before other events.
            eventLoop.execute(() -> doNotifySubscriber(subscription));
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
                doHandleCloseEvent(subscription, (CloseEvent) queue.remove());
                break;
            }

            if (demand == 0) {
                break;
            }

            if (o instanceof AwaitDemandFuture) {
                final AwaitDemandFuture f = (AwaitDemandFuture) queue.remove();
                f.complete(null);
                continue;
            }

            demand--;

            @SuppressWarnings("unchecked")
            final T obj = (T) queue.remove();
            doNotifySubscriberOfObject(subscription, obj);
        }
    }

    private void doNotifySubscriberOfObject(SubscriptionImpl subscription, T obj) {
        final Subscriber<Object> subscriber = subscription.subscriber();
        obj = prepareObjectForNotification(subscription, obj);

        if (subscription.needsDirectInvocation()) {
            inOnNext = true;
            try {
                subscriber.onNext(obj);
            } finally {
                inOnNext = false;
            }
        } else {
            final T published = obj;
            subscription.executor().execute(() -> subscriber.onNext(published));
        }
    }

    private void doHandleCloseEvent(SubscriptionImpl subscription, CloseEvent event) {
        if (!invokedOnSubscribe) {
            // Subscriber.onSubscribe() was not invoked yet.
            // Reschedule the notification so that onSubscribe() is invoked before event.
            eventLoop.execute(() -> doHandleCloseEvent(subscription, event));
            return;
        }
        doSetState(State.CLEANUP);
        notifySubscriberOfCloseEvent(subscription, event);
    }

    private void cancelOrAbort(boolean cancel) {
        if (eventLoop.inEventLoop()) {
            doCancelOrAbort(cancel);
        } else {
            eventLoop.execute(() -> doCancelOrAbort(cancel));
        }
    }

    private void doCancelOrAbort(boolean cancel) {
        if (state == State.OPEN) {
            doSetState(State.CLEANUP);
            final CloseEvent closeEvent;
            if (cancel) {
                closeEvent = Flags.verboseExceptions() ?
                             new CloseEvent(CancelledSubscriptionException.get()) : CANCELLED_CLOSE;
            } else {
                closeEvent = Flags.verboseExceptions() ?
                             new CloseEvent(AbortedStreamException.get()) : ABORTED_CLOSE;
            }
            doAddObjectOrEvent(closeEvent);
            return;
        }

        switch (state) {
            case CLOSED:
                // close() has been called before cancel(). There's no need to push a CloseEvent,
                // but we need to ensure the closeFuture is notified and any pending objects are removed.
                doSetState(State.CLEANUP);
                cleanup();
                break;
            case CLEANUP:
                // Cleaned up already.
                break;
            default: // OPEN: should never reach here.
                throw new Error();
        }
    }

    private void doSetAbortedSubscription() {
        subscription = new SubscriptionImpl(this, AbortingSubscriber.get(),
                                            ImmediateEventExecutor.INSTANCE, false);
        // We don't need to invoke onSubscribe() for AbortingSubscriber because it's just a placeholder.
        invokedOnSubscribe = true;
    }

    private void cleanup() {
        cleanupQueue(subscription, queue);
    }

    /**
     * Indicates an invocation of {@link EventLoopStreamMessage#EventLoopStreamMessage()} with no available
     * event loop, likely causing significant performance issues.
     */
    private static class UnexpectedEventLoopException extends RuntimeException {
        private static final long serialVersionUID = 5610415039321743416L;
    }
}
