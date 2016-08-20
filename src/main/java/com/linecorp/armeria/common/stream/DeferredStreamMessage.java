/*
 * Copyright 2016 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.util.CompletionActions;

/**
 * A {@link StreamMessage} whose stream is published later by another {@link StreamMessage}. It is useful when
 * your {@link StreamMessage} will not be instantiated early.
 *
 * @param <T> the type of element signaled
 */
public class DeferredStreamMessage<T> implements StreamMessage<T> {

    @SuppressWarnings({ "AtomicFieldUpdaterIssues", "rawtypes" })
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, StreamMessage> delegateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DeferredStreamMessage.class, StreamMessage.class, "delegate");
    @SuppressWarnings({ "AtomicFieldUpdaterIssues", "rawtypes" })
    private static final AtomicReferenceFieldUpdater<DeferredStreamMessage, Subscriber> subscriberUpdater =
            AtomicReferenceFieldUpdater.newUpdater(DeferredStreamMessage.class, Subscriber.class, "subscriber");

    private static final Subscriber<?> ABORTED_SUBSCRIBER = new Subscriber<Object>() {
        @Override
        public void onSubscribe(Subscription s) {}

        @Override
        public void onNext(Object o) {}

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}
    };

    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    @SuppressWarnings("unused") // Updated only via delegateUpdater
    private volatile StreamMessage<T> delegate;
    @SuppressWarnings("unused") // Updated only via subscriberUpdater
    private volatile Subscriber<T> subscriber;
    private volatile Executor subscriberExecutor;
    private volatile boolean abortPending;

    /**
     * Sets the delegate {@link HttpResponse} which will publish the stream actually.
     *
     * @throws IllegalStateException if the delegate has been set already or
     *                               if {@link #close()} or {@link #close(Throwable)} was called already.
     */
    protected void delegate(StreamMessage<T> delegate) {
        requireNonNull(delegate, "delegate");
        if (!delegateUpdater.compareAndSet(this, null, delegate)) {
            throw new IllegalStateException("delegate set already");
        }

        delegate.closeFuture().handle((unused, cause) -> {
            if (cause == null) {
                closeFuture.complete(null);
            } else {
                closeFuture.completeExceptionally(cause);
            }
            return null;
        }).exceptionally(CompletionActions::log);

        final Subscriber<T> subscriber = this.subscriber;
        if (subscriber != null && subscriber != ABORTED_SUBSCRIBER) {
            subscribeToDelegate(subscriber, subscriberExecutor);
        }

        if (abortPending) {
            delegate.abort();
        }
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
        return !closeFuture.isDone();
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
    public CompletableFuture<Void> closeFuture() {
        return closeFuture;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        subscribe0(subscriber, null);
    }

    @Override
    public void subscribe(Subscriber<? super T> s, Executor executor) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        subscribe0(subscriber, executor);
    }

    private void subscribe0(Subscriber<? super T> subscriber, Executor executor) {
        if (!subscriberUpdater.compareAndSet(this, null, subscriber)) {
            if (this.subscriber == ABORTED_SUBSCRIBER) {
                throw new IllegalStateException("cannot subscribe to an aborted publisher");
            } else {
                throw new IllegalStateException("subscribed by other subscriber already: " + this.subscriber);
            }
        }

        subscriberExecutor = executor;
        subscribeToDelegate(subscriber, executor);
    }

    private void subscribeToDelegate(Subscriber<? super T> subscriber, Executor executor) {
        final StreamMessage<T> delegate = this.delegate;
        if (delegate != null) {
            if (executor == null) {
                delegate.subscribe(subscriber);
            } else {
                delegate.subscribe(subscriber, executor);
            }
        }
    }

    @Override
    public void abort() {
        abortPending = true;

        // Prevent the future subscription.
        subscriberUpdater.compareAndSet(this, null, ABORTED_SUBSCRIBER);

        final StreamMessage<T> delegate = this.delegate;
        if (delegate != null) {
            delegate.abort();
        } else {
            closeFuture.completeExceptionally(CancelledSubscriptionException.get());
        }
    }
}
