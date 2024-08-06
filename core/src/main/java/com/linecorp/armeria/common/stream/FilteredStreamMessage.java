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

import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsNotifyCancellation;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.containsWithPooledObjects;
import static com.linecorp.armeria.internal.common.stream.InternalStreamMessageUtil.toSubscriptionOptions;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.stream.StreamMessageUtil;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link StreamMessage} that filters objects as they are published. The filtering
 * will happen from an I/O thread, meaning the order of the filtering will match the
 * order that the {@code delegate} processes the objects in.
 */
public abstract class FilteredStreamMessage<T, U> extends AggregationSupport implements StreamMessage<U> {

    private static final Logger logger = LoggerFactory.getLogger(FilteredStreamMessage.class);

    private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
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
     *
     * <p>Note that this method may raise an {@link Exception}. In that case, the caller should stop the
     * completion process and propagate the {@link Exception} to the {@link Subscriber}.
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
        return completionFuture;
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

    private void subscribe(Subscriber<? super U> subscriber, EventExecutor executor,
                           boolean withPooledObjects, boolean notifyCancellation) {
        final FilteringSubscriber filteringSubscriber = new FilteringSubscriber(
                subscriber, withPooledObjects);
        final SubscriptionOption[] options = toSubscriptionOptions(filterSupportsPooledObjects,
                                                                   notifyCancellation);
        upstream.subscribe(filteringSubscriber, executor, options);
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

    private final class FilteringSubscriber implements Subscription, Subscriber<T> {

        private final Subscriber<? super U> delegate;
        private final boolean subscribedWithPooledObjects;

        private boolean completed;
        @Nullable
        private Subscription upstream;

        FilteringSubscriber(Subscriber<? super U> delegate, boolean subscribedWithPooledObjects) {
            this.delegate = requireNonNull(delegate, "delegate");
            this.subscribedWithPooledObjects = subscribedWithPooledObjects;
        }

        @Override
        public void request(long n) {
            assert upstream != null;
            upstream.request(n);
        }

        @Override
        public void cancel() {
            assert upstream != null;
            onCancellation(delegate);
            completionFuture.completeExceptionally(CancelledSubscriptionException.get());
            upstream.cancel();
        }

        @Override
        public void onSubscribe(Subscription s) {
            upstream = s;
            try {
                beforeSubscribe(delegate, this);
            } catch (Throwable ex) {
                s.cancel();
                logger.warn("Unexpected exception from {}#beforeSubscribe()",
                            FilteredStreamMessage.this.getClass().getName(), ex);
                return;
            }

            delegate.onSubscribe(this);
        }

        @Override
        public void onNext(T o) {
            U filtered;
            try {
                filtered = filter(o);
            } catch (Throwable ex) {
                // onError(ex) should be called before upstream.cancel() to deliver the cause to downstream.
                // upstream.cancel() may close downstream with CancelledSubscriptionException before sending
                // the actual cause.
                onError(ex);

                assert upstream != null;
                upstream.cancel();
                return;
            }

            if (completed) {
                // onError(Throwable) or onComplete() has been called in filter().
                StreamMessageUtil.closeOrAbort(filtered);
                return;
            }
            if (!subscribedWithPooledObjects) {
                filtered = PooledObjects.copyAndClose(filtered);
            }
            delegate.onNext(filtered);
        }

        @Override
        public void onError(Throwable t) {
            if (completed) {
                return;
            }
            completed = true;
            final Throwable filteredCause = beforeError(delegate, t);
            if (filteredCause != null) {
                delegate.onError(filteredCause);
                completionFuture.completeExceptionally(filteredCause);
            } else {
                if (logger.isWarnEnabled()) {
                    logger.warn("{}#beforeError() returned null. Using the original exception: {}",
                                FilteredStreamMessage.this.getClass().getName(), t.toString());
                }
                delegate.onError(t);
                completionFuture.completeExceptionally(t);
            }
        }

        @Override
        public void onComplete() {
            if (completed) {
                return;
            }
            completed = true;
            try {
                beforeComplete(delegate);
                delegate.onComplete();
                completionFuture.complete(null);
            } catch (Exception cause) {
                delegate.onError(cause);
                completionFuture.completeExceptionally(cause);
            }
        }
    }
}
