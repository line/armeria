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

import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.google.common.base.MoreObjects;

import io.netty.util.concurrent.EventExecutor;

/**
 * Wraps a {@link StreamMessage} and forwards its method invocations to {@code delegate}.
 * @param <T> the type of elements
 */
public class StreamMessageWrapper<T> implements StreamMessage<T> {

    private final StreamMessage<? extends T> delegate;

    /**
     * Creates a new instance that wraps a {@code delegate}.
     */
    public StreamMessageWrapper(StreamMessage<? extends T> delegate) {
        requireNonNull(delegate, "delegate");
        this.delegate = delegate;
    }

    /**
     * Returns the {@link StreamMessage} being decorated.
     */
    protected final StreamMessage<? extends T> delegate() {
        return delegate;
    }

    @Override
    public boolean isOpen() {
        return delegate().isOpen();
    }

    @Override
    public boolean isEmpty() {
        return delegate().isEmpty();
    }

    @Override
    public CompletableFuture<Void> completionFuture() {
        return delegate().completionFuture();
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        delegate().subscribe(s);
    }

    @Override
    public void subscribe(Subscriber<? super T> s, boolean withPooledObjects) {
        if (withPooledObjects) {
            delegate().subscribe(s, WITH_POOLED_OBJECTS);
        } else {
            delegate().subscribe(s);
        }
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, SubscriptionOption... options) {
        delegate().subscribe(subscriber, options);
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        delegate().subscribe(subscriber, executor);
    }

    @Override
    public void subscribe(Subscriber<? super T> s, EventExecutor executor, boolean withPooledObjects) {
        if (withPooledObjects) {
            delegate().subscribe(s, executor, WITH_POOLED_OBJECTS);
        } else {
            delegate().subscribe(s, executor);
        }
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        delegate().subscribe(subscriber, executor, options);
    }

    @Override
    public CompletableFuture<List<T>> drainAll() {
        return cast(delegate().drainAll());
    }

    @Override
    public CompletableFuture<List<T>> drainAll(boolean withPooledObjects) {
        if (withPooledObjects) {
            return drainAll(WITH_POOLED_OBJECTS);
        } else {
            return drainAll();
        }
    }

    @Override
    public CompletableFuture<List<T>> drainAll(SubscriptionOption... options) {
        return cast(delegate().drainAll(options));
    }

    @Override
    public CompletableFuture<List<T>> drainAll(EventExecutor executor) {
        return cast(delegate().drainAll(executor));
    }

    @Override
    public CompletableFuture<List<T>> drainAll(EventExecutor executor, boolean withPooledObjects) {
        if (withPooledObjects) {
            return drainAll(executor, WITH_POOLED_OBJECTS);
        } else {
            return drainAll(executor);
        }
    }

    @Override
    public CompletableFuture<List<T>> drainAll(EventExecutor executor, SubscriptionOption... options) {
        return cast(delegate().drainAll(executor, options));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<List<T>> cast(CompletableFuture<? extends List<? extends T>> future) {
        return (CompletableFuture<List<T>>) future;
    }

    @Override
    public void abort() {
        delegate().abort();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("delegate", delegate()).toString();
    }
}
