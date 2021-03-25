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

import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.common.stream.StreamMessageUtil.containsWithPooledObjects;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link StreamMessage} that filters objects as they are published. The filtering
 * will happen from an I/O thread, meaning the order of the filtering will match the
 * order that the {@code delegate} processes the objects in.
 */
public abstract class FilteredStreamMessage<T, U> implements StreamMessage<U> {

    private static final Logger logger = LoggerFactory.getLogger(FilteredStreamMessage.class);

    private final StreamMessage<T> upstream;
    private final boolean filterSupportsPooledObjects;

    /**
     * Creates a new {@link FilteredStreamMessage} that filters objects published by {@code upstream}
     * before passing to a subscriber.
     */
    protected FilteredStreamMessage(StreamMessage<T> upstream) {
        this(upstream, false);
    }

    /**
     * (Advanced users only) Creates a new {@link FilteredStreamMessage} that filters objects published by
     * {@code upstream} before passing to a subscriber.
     *
     * @param withPooledObjects if {@code true}, {@link #filter(Object)} receives the pooled {@link HttpData}
     *                          as is, without making a copy. If you don't know what this means,
     *                          use {@link #FilteredStreamMessage(StreamMessage)}.
     * @see PooledObjects
     */
    @UnstableApi
    protected FilteredStreamMessage(StreamMessage<T> upstream, boolean withPooledObjects) {
        this.upstream = requireNonNull(upstream, "upstream");
        filterSupportsPooledObjects = withPooledObjects;
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
     * subscription. This method may rewrite the {@code cause} and then return a new one so that the new
     * {@link Throwable} would be passed to {@link Subscriber#onError(Throwable)}.
     */
    @Nullable
    protected Throwable beforeError(Subscriber<? super U> subscriber, Throwable cause) {
        return cause;
    }

    /**
     * A callback executed when this {@link StreamMessage} is canceled by the {@link Subscriber}.
     */
    protected void onCancellation(Subscriber<? super U> subscriber) {}

    @Override
    public final boolean isOpen() {
        return upstream.isOpen();
    }

    @Override
    public final boolean isEmpty() {
        return upstream.isEmpty();
    }

    @Override
    public final long demand() {
        return upstream.demand();
    }

    @Override
    public final CompletableFuture<Void> whenComplete() {
        return upstream.whenComplete();
    }

    @Override
    public final void subscribe(Subscriber<? super U> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, false, false);
    }

    @Override
    public final void subscribe(Subscriber<? super U> subscriber, EventExecutor executor,
                                SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");
        final boolean withPooledObjects = containsWithPooledObjects(options);
        final boolean notifyCancellation = containsNotifyCancellation(options);
        subscribe(subscriber, executor, withPooledObjects, notifyCancellation);
    }

    private void subscribe(Subscriber<? super U> subscriber, EventExecutor executor, boolean withPooledObjects,
                           boolean notifyCancellation) {
        final FilteringSubscriber filteringSubscriber = new FilteringSubscriber(
                subscriber, withPooledObjects, notifyCancellation);
        if (filterSupportsPooledObjects) {
            upstream.subscribe(filteringSubscriber, executor,
                               SubscriptionOption.NOTIFY_CANCELLATION, SubscriptionOption.WITH_POOLED_OBJECTS);
        } else {
            upstream.subscribe(filteringSubscriber, executor, SubscriptionOption.NOTIFY_CANCELLATION);
        }
    }

    @Override
    public final EventExecutor defaultSubscriberExecutor() {
        return upstream.defaultSubscriberExecutor();
    }

    @Override
    public final void abort() {
        upstream.abort();
    }

    @Override
    public final void abort(Throwable cause) {
        upstream.abort(requireNonNull(cause, "cause"));
    }

    private final class FilteringSubscriber implements Subscriber<T> {

        private final Subscriber<? super U> delegate;
        private final boolean subscribedWithPooledObjects;
        private final boolean notifyCancellation;

        FilteringSubscriber(Subscriber<? super U> delegate, boolean subscribedWithPooledObjects,
                            boolean notifyCancellation) {
            this.delegate = requireNonNull(delegate, "delegate");
            this.subscribedWithPooledObjects = subscribedWithPooledObjects;
            this.notifyCancellation = notifyCancellation;
        }

        @Override
        public void onSubscribe(Subscription s) {
            beforeSubscribe(delegate, s);
            delegate.onSubscribe(s);
        }

        @Override
        public void onNext(T o) {
            U filtered = filter(o);
            if (!subscribedWithPooledObjects) {
                filtered = PooledObjects.copyAndClose(filtered);
            }
            delegate.onNext(filtered);
        }

        @Override
        public void onError(Throwable t) {
            if (t instanceof CancelledSubscriptionException) {
                onCancellation(delegate);
                if (!notifyCancellation) {
                    return;
                }
            }

            final Throwable filteredCause = beforeError(delegate, t);
            if (filteredCause != null) {
                delegate.onError(filteredCause);
            } else {
                if (logger.isWarnEnabled()) {
                    logger.warn("{}#beforeError() returned null. Using the original exception: {}",
                                FilteredStreamMessage.this.getClass().getName(), t.toString());
                }
                delegate.onError(t);
            }
        }

        @Override
        public void onComplete() {
            beforeComplete(delegate);
            delegate.onComplete();
        }
    }
}
