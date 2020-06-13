/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.unsafe;

import static com.linecorp.armeria.common.unsafe.UnsafeStreamUtil.withPooledObjects;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.stream.InternalSubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link StreamMessage} of {@link HttpObject} which exposes unsafe APIs for subscribing to pooled objects
 * from the stream.
 */
public interface PooledHttpStreamMessage extends StreamMessage<HttpObject> {

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     */
    default void subscribeWithPooledObjects(Subscriber<? super HttpObject> subscriber) {
        subscribeWithPooledObjects(subscriber, defaultSubscriberExecutor());
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param options {@link SubscriptionOption}s to subscribe with
     */
    default void subscribeWithPooledObjects(
            Subscriber<? super HttpObject> subscriber, SubscriptionOption... options) {
        subscribeWithPooledObjects(subscriber, defaultSubscriberExecutor(), options);
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param executor the executor to subscribe
     */
    default void subscribeWithPooledObjects(Subscriber<? super HttpObject> subscriber, EventExecutor executor) {
        subscribe(subscriber, executor, InternalSubscriptionOption.WITH_POOLED_OBJECTS);
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param executor the executor to subscribe
     * @param options {@link SubscriptionOption}s to subscribe with
     */
    default void subscribeWithPooledObjects(
            Subscriber<? super HttpObject> subscriber, EventExecutor executor, SubscriptionOption... options) {
        subscribe(subscriber, executor, withPooledObjects(options));
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber} without pooled objects. When
     * operating on {@link PooledHttpStreamMessage} this should be avoided.
     *
     * @deprecated Use {@link #subscribeWithPooledObjects(Subscriber)}.
     */
    @Override
    @Deprecated
    default void subscribe(Subscriber<? super HttpObject> subscriber) {
        StreamMessage.super.subscribe(subscriber);
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber} without pooled objects. When
     * operating on {@link PooledHttpStreamMessage} this should be avoided.
     *
     * @deprecated Use {@link #subscribeWithPooledObjects(Subscriber, SubscriptionOption...)}.
     */
    @Override
    @Deprecated
    default void subscribe(Subscriber<? super HttpObject> subscriber, SubscriptionOption... options) {
        subscribe(subscriber, defaultSubscriberExecutor(), options);
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber} without pooled objects. When
     * operating on {@link PooledHttpStreamMessage} this should be avoided.
     *
     * @deprecated Use {@link #subscribeWithPooledObjects(Subscriber, EventExecutor)}.
     */
    @Override
    @Deprecated
    void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor);

    /**
     * Requests to start streaming data to the specified {@link Subscriber} without pooled objects. When
     * operating on {@link PooledHttpStreamMessage} this should be avoided.
     *
     * @deprecated Use {@link #subscribeWithPooledObjects(Subscriber, EventExecutor, SubscriptionOption...)}.
     */
    @Override
    @Deprecated
    void subscribe(
            Subscriber<? super HttpObject> subscriber, EventExecutor executor, SubscriptionOption... options);
}
