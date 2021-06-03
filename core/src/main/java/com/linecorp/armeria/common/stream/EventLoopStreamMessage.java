/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.common.stream.SubscriberUtil.abortedOrLate;
import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsWithPooledObjects;
import static com.linecorp.armeria.internal.common.stream.StreamMessageUtil.touchOrCopyAndClose;
import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.AbstractStreamMessageAndWriter.AwaitDemandFuture;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import com.linecorp.armeria.internal.common.stream.StreamMessageUtil;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

/**
 * A {@link StreamMessage} implementation optimized to run inside a single event loop.
 *
 * <p>Methods inherited from {@link StreamWriter} should be run from the specified event loop,
 * whereas methods inherited from {@link StreamMessage} are scheduled in the specified executor.
 *
 * <p>This class is originally intended for internal use.
 */
@UnstableApi
public class EventLoopStreamMessage<T> implements StreamMessageAndWriter<T>, Subscription,
                                                  StreamCallbackListener<T> {
    private static final Logger logger = LoggerFactory.getLogger(EventLoopStreamMessage.class);

    private enum State {
        OPEN,
        CLOSED,
        CLEANUP,
    }

    static final CloseEvent SUCCESSFUL_CLOSE = new CloseEvent();
    static final CloseEvent CANCELLED_CLOSE = new CloseEvent(CancelledSubscriptionException.INSTANCE);
    static final CloseEvent ABORTED_CLOSE = new CloseEvent(AbortedStreamException.INSTANCE);

    static CloseEvent newCloseEvent(Throwable cause) {
        if (cause == CancelledSubscriptionException.INSTANCE) {
            return CANCELLED_CLOSE;
        } else if (cause == AbortedStreamException.INSTANCE) {
            return ABORTED_CLOSE;
        } else {
            return new CloseEvent(cause);
        }
    }

    private final EventExecutor eventLoop;
    @VisibleForTesting
    final Queue<Object> queue;
    private final CompletableFuture<Void> completionFuture;

    private volatile State state = State.OPEN;
    private volatile long demand;
    private volatile boolean wroteAny;
    private boolean inOnNext;

    @Nullable
    @VisibleForTesting
    Subscriber<? super T> subscriber;
    @Nullable
    private EventExecutor executor;
    private boolean withPooledObjects;
    private boolean notifyCancellation;
    private StreamCallbackListener<T> callbackListener = this;

    /**
     * TBU.
     */
    public EventLoopStreamMessage(EventExecutor eventLoop) {
        this.eventLoop = eventLoop;
        queue = new ArrayDeque<>();
        completionFuture = new EventLoopCheckingFuture<>();
    }

    @Override
    public void setCallbackListener(StreamCallbackListener<T> callbackListener) {
        this.callbackListener = requireNonNull(callbackListener, "callbackListener");
    }

    protected EventExecutor eventLoop() {
        return eventLoop;
    }

    @Override
    public final boolean isOpen() {
        return state == State.OPEN;
    }

    @Override
    public boolean tryWrite(T obj) {
        requireNonNull(obj, "obj");
        assert eventLoop.inEventLoop();

        if (!isOpen()) {
            StreamMessageUtil.closeOrAbort(obj);
            return false;
        }
        wroteAny = true;
        addObject(obj);
        return true;
    }

    @Override
    public CompletableFuture<Void> whenConsumed() {
        final AwaitDemandFuture f = new AwaitDemandFuture();
        if (!isOpen()) {
            f.completeExceptionally(ClosedStreamException.get());
            return f;
        }

        addObject(f);
        return f;
    }

    private void addObject(Object obj) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> addObject(obj));
            return;
        }
        queue.add(obj);
        tryProcessEventQueue();
    }

    @Override
    public final boolean tryClose(Throwable cause) {
        assert eventLoop.inEventLoop();
        if (!isOpen()) {
            return false;
        }
        close(cause);
        return true;
    }

    @Override
    public void close() {
        close0(SUCCESSFUL_CLOSE);
    }

    @Override
    public void close(Throwable cause) {
        requireNonNull(cause, "cause");
        if (cause instanceof CancelledSubscriptionException) {
            throw new IllegalArgumentException("cause: " + cause + " (must use Subscription.cancel())");
        }
        close0(newCloseEvent(cause));
    }

    private void close0(CloseEvent closeEvent) {
        assert eventLoop.inEventLoop();

        if (state != State.OPEN) {
            return;
        }
        state = State.CLOSED;

        addObject(closeEvent);
    }

    @Override
    public final boolean isEmpty() {
        return !isOpen() && !wroteAny;
    }

    @Override
    public final long demand() {
        return demand;
    }

    @Override
    public final CompletableFuture<Void> whenComplete() {
        return completionFuture;
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                                SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");
        if (eventLoop.inEventLoop()) {
            doSubscribe0(subscriber, executor, options);
        } else {
            eventLoop.execute(() -> doSubscribe0(subscriber, executor, options));
        }
    }

    private void doSubscribe0(Subscriber<? super T> subscriber, EventExecutor executor,
                              SubscriptionOption... options) {
        assert eventLoop.inEventLoop();
        if (this.subscriber != null) {
            notifyOnSubscribeAndError(NoopSubscription.get(), subscriber,
                                      abortedOrLate(this.subscriber), executor);
            return;
        }

        this.subscriber = subscriber;
        this.executor = executor;
        withPooledObjects = containsWithPooledObjects(options);
        notifyCancellation = containsNotifyCancellation(options);

        notifyOnSubscribe(this, subscriber, executor, options);
        tryProcessEventQueue();
    }

    @Override
    public final void abort() {
        abort0(ABORTED_CLOSE);
    }

    @Override
    public final void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        abort0(newCloseEvent(cause));
    }

    private void abort0(CloseEvent closeEvent) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> abort0(closeEvent));
            return;
        }

        if (subscriber == null) {
            subscriber = AbortingSubscriber.get(closeEvent.cause());
            executor = ImmediateEventExecutor.INSTANCE;
        }
        scheduleClose(closeEvent);
    }

    @Override
    public final void request(long n) {
        if (n <= 0) {
            // Just abort the publisher so subscriber().onError(e) is called and resources are cleaned up.
            abort(new IllegalArgumentException(
                    "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
            return;
        }
        request0(n);
    }

    private void request0(long demand) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> request0(demand));
            return;
        }
        callbackListener.onRequest(demand);

        if (this.demand >= Long.MAX_VALUE - demand) {
            this.demand = Long.MAX_VALUE;
        } else {
            this.demand += demand;
        }

        tryProcessEventQueue();
    }

    @Override
    public final void cancel() {
        scheduleClose(CANCELLED_CLOSE);
    }

    private void scheduleClose(CloseEvent closeEvent) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> scheduleClose(closeEvent));
            return;
        }
        assert subscriber != null;
        assert executor != null;
        scheduleClose(subscriber, closeEvent, executor);
    }

    private void scheduleClose(Subscriber<?> subscriber,
                               CloseEvent closeEvent, EventExecutor executor) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> scheduleClose(subscriber, closeEvent, executor));
            return;
        }

        if (state == State.CLEANUP) {
            return;
        }
        state = State.CLEANUP;

        final Throwable cause = closeEvent.cause();
        try {
            if (cause == null) {
                notifyOnComplete(subscriber, executor, completionFuture);
            } else {
                notifyOnError(subscriber, cause, executor, completionFuture, notifyCancellation);
            }
        } finally {
            clearSubscriber();
            drain(cause);
        }
    }

    private void clearSubscriber() {
        assert eventLoop.inEventLoop();
        if (!(subscriber instanceof AbortingSubscriber)) {
            subscriber = NeverInvokedSubscriber.get();
        }
    }

    private void drain(@Nullable Throwable cause) {
        assert eventLoop.inEventLoop();

        while (!queue.isEmpty()) {
            final Object o = queue.remove();
            if (o instanceof CloseEvent) {
                continue;
            }

            @SuppressWarnings("unchecked")
            final T t = (T) o;
            callbackListener.onRemoval(t);
            StreamMessageUtil.closeOrAbort(t, cause);
        }
    }

    private void tryProcessEventQueue() {
        assert eventLoop.inEventLoop();

        if (subscriber == null) {
            return;
        }

        if (inOnNext) {
            return;
        }

        while (!queue.isEmpty()) {
            final Object o = queue.peek();
            if (o instanceof CloseEvent) {
                scheduleClose((CloseEvent) queue.remove());
                return;
            }

            if (o instanceof AwaitDemandFuture) {
                notifyAwaitDemandFuture();
                continue;
            }

            if (!processEvent()) {
                break;
            }
        }
    }

    private boolean processEvent() {
        assert eventLoop.inEventLoop();

        assert state != State.CLEANUP;
        assert subscriber != null;
        assert executor != null;

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
            callbackListener.onRemoval(o);
            o = touchOrCopyAndClose(o, withPooledObjects);
            notifyOnNext(o, subscriber, executor);
        } finally {
            inOnNext = false;
        }

        return true;
    }

    private void notifyAwaitDemandFuture() {
        @SuppressWarnings("unchecked")
        final CompletableFuture<Void> f = (CompletableFuture<Void>) queue.remove();
        f.complete(null);
    }

    private static void notifyOnSubscribeAndError(Subscription subscription, Subscriber<?> subscriber,
                                                  Throwable cause, EventExecutor executor) {
        if (!executor.inEventLoop()) {
            executor.execute(() -> notifyOnSubscribeAndError(subscription, subscriber, cause, executor));
            return;
        }
        try {
            subscriber.onSubscribe(subscription);
            subscriber.onError(cause);
        } catch (Throwable t) {
            throwIfFatal(t);
            logger.warn("Subscriber should not throw an exception. subscriber: {}", subscriber, t);
        }
    }

    private void notifyOnSubscribe(Subscription subscription, Subscriber<?> subscriber,
                                   EventExecutor executor, SubscriptionOption... options) {
        if (!executor.inEventLoop()) {
            executor.execute(() -> notifyOnSubscribe(subscription, subscriber, executor, options));
            return;
        }
        try {
            callbackListener.onSubscribe(executor, options);
            subscriber.onSubscribe(subscription);
        } catch (Throwable t) {
            scheduleClose(subscriber, newCloseEvent(t), executor);
            throwIfFatal(t);
            logger.warn("Subscriber.onSubscribe() should not raise an exception. subscriber: {}",
                        subscriber, t);
        }
    }

    private void notifyOnNext(T o, Subscriber<? super T> subscriber, EventExecutor executor) {
        if (!executor.inEventLoop()) {
            executor.execute(() -> notifyOnNext(o, subscriber, executor));
            return;
        }

        try {
            subscriber.onNext(o);
        } catch (Throwable t) {
            scheduleClose(newCloseEvent(t));
            throwIfFatal(t);
            logger.warn("Subscriber.onNext({}) should not raise an exception. subscriber: {}",
                        o, subscriber, t);
        }
    }

    private static void notifyOnComplete(Subscriber<?> subscriber, EventExecutor executor,
                                         CompletableFuture<?> completionFuture) {
        if (!executor.inEventLoop()) {
            executor.execute(() -> notifyOnComplete(subscriber, executor, completionFuture));
            return;
        }
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

    private static void notifyOnError(Subscriber<?> subscriber, Throwable cause, EventExecutor executor,
                                      CompletableFuture<Void> completionFuture, boolean notifyCancellation) {
        if (!executor.inEventLoop()) {
            executor.execute(() -> notifyOnError(subscriber, cause, executor, completionFuture,
                                                 notifyCancellation));
            return;
        }

        try {
            if (notifyCancellation || !(cause instanceof CancelledSubscriptionException)) {
                subscriber.onError(cause);
            }
            completionFuture.completeExceptionally(cause);
        } catch (Throwable t) {
            final Exception composite = new CompositeException(t, cause);
            completionFuture.completeExceptionally(composite);
            throwIfFatal(t);
            logger.warn("Subscriber.onError() should not raise an exception. subscriber: {}",
                        subscriber, composite);
        }
    }

    private static class CloseEvent {
        @Nullable
        private final Throwable cause;

        CloseEvent() {
            cause = null;
        }

        CloseEvent(Throwable cause) {
            this.cause = cause;
        }

        @Nullable
        public Throwable cause() {
            return cause;
        }
    }
}
