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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A {@link StreamMessage} that filters objects as they are published. The filtering
 * will happen from an I/O thread, meaning the order of the filtering will match the
 * order that the {@code delegate} processes the objects in.
 */
public abstract class FilteredStreamMessage<T, U> implements StreamMessage<U> {

    private final StreamMessage<T> delegate;

    /**
     * Creates a new {@link FilteredStreamMessage} that filters objects published by {@code delegate}
     * before passing to a subscriber.
     */
    protected FilteredStreamMessage(StreamMessage<T> delegate) {
        requireNonNull(delegate, "delegate");
        this.delegate = delegate;
    }

    /**
     * The filter to apply to published objects. The result of the filter is passed on
     * to the delegate subscriber.
     */
    protected abstract U filter(T obj);

    /**
     * A callback executed just before calling {@link Subscriber#onSubscribe(Subscription)} on
     * {@code subscriber}. Override this method to execute any initialization logic that may be needed.
     */
    protected void beforeSubscribe(Subscriber<? super U> subscriber, Subscription subscription) {}

    /**
     * A callback executed just before calling {@link Subscriber#onComplete()} on {@code subscriber}.
     * Override this method to execute any cleanup logic that may be needed before completing the
     * subscription.
     */
    protected void beforeComplete(Subscriber<? super U> subscriber) {}

    /**
     * A callback executed just before calling {@link Subscriber#onError(Throwable)} on {@code subscriber}.
     * Override this method to execute any cleanup logic that may be needed before failing the
     * subscription.
     */
    protected void beforeError(Subscriber<? super U> subscriber, Throwable cause) {}

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public CompletableFuture<Void> completionFuture() {
        return delegate.completionFuture();
    }

    @Override
    public void subscribe(Subscriber<? super U> subscriber) {
        requireNonNull(subscriber, "subscriber");
        delegate.subscribe(new FilteringSubscriber(subscriber));
    }

    @Override
    public void subscribe(Subscriber<? super U> subscriber, boolean withPooledObjects) {
        requireNonNull(subscriber, "subscriber");
        delegate.subscribe(new FilteringSubscriber(subscriber), withPooledObjects);
    }

    @Override
    public void subscribe(Subscriber<? super U> subscriber, Executor executor) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        delegate.subscribe(new FilteringSubscriber(subscriber), executor);
    }

    @Override
    public void subscribe(Subscriber<? super U> subscriber, Executor executor, boolean withPooledObjects) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        delegate.subscribe(new FilteringSubscriber(subscriber), executor, withPooledObjects);
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    private final class FilteringSubscriber implements Subscriber<T> {

        private final Subscriber<? super U> delegate;

        FilteringSubscriber(Subscriber<? super U> delegate) {
            requireNonNull(delegate, "delegate");
            this.delegate = delegate;
        }

        @Override
        public void onSubscribe(Subscription s) {
            beforeSubscribe(delegate, s);
            delegate.onSubscribe(s);
        }

        @Override
        public void onNext(T t) {
            delegate.onNext(filter(t));
        }

        @Override
        public void onError(Throwable t) {
            beforeError(delegate, t);
            delegate.onError(t);
        }

        @Override
        public void onComplete() {
            beforeComplete(delegate);
            delegate.onComplete();
        }
    }
}
