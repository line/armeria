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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.util.ReferenceCounted;

/**
 * A variant of <a href="http://www.reactive-streams.org/">Reactive Streams</a> {@link Publisher}, which allows
 * only one {@link Subscriber}. Unlike a usual {@link Publisher}, a {@link StreamMessage} can stream itself
 * only once.  It has the following additional operations on top of what the Reactive Streams API provides:
 * <ul>
 *   <li>{@link #isOpen()}</li>
 *   <li>{@link #isEmpty()}</li>
 *   <li>{@link #closeFuture()}</li>
 *   <li>{@link #abort()}</li>
 * </ul>
 *
 * <h3>When is a {@link StreamMessage} open?</h3>
 *
 * <p>A {@link StreamMessage} is open since its instantiation until:
 * <ul>
 *   <li>the {@link Subscriber} consumes all elements and {@link Subscriber#onComplete()} is invoked,</li>
 *   <li>an error occurred and {@link Subscriber#onError(Throwable)} is invoked,</li>
 *   <li>the {@link Subscription} has been cancelled or</li>
 *   <li>{@link #abort()} has been requested.</li>
 * </ul>
 *
 * <h3>Getting notified when a {@link StreamMessage} is closed</h3>
 *
 * <p>{@link Subscriber#onComplete()} and {@link Subscriber#onError(Throwable)} will not be invoked when its
 * {@link Subscription} has been {@linkplain Subscription#cancel() cancelled} or {@linkplain #abort() aborted}.
 * Use {@link #closeFuture()} if you need to get notified when a {@link StreamMessage} is closed regardless of
 * whether it's been cancelled, aborted or closed.
 *
 * <h3>Publication and Consumption of {@link ReferenceCounted} objects</h3>
 *
 * <p>{@link StreamMessage} will reject the publication request of a {@link ReferenceCounted} object except
 * {@link ByteBuf} and {@link ByteBufHolder}.
 *
 * <p>{@link StreamMessage} will discard the publication request of a {@link ByteBuf} or a {@link ByteBufHolder}
 * silently and release it automatically when the publication is attempted after the stream is closed.
 *
 * <p>For {@link ByteBuf} and {@link ByteBufHolder}, {@link StreamMessage} will convert them into their
 * respective unpooled versions that never leak, so that the {@link Subscriber} does not need to worry about
 * leaks.
 *
 * <p>If a {@link Subscriber} does not want a {@link StreamMessage} to make a copy of a {@link ByteBufHolder},
 * use {@link #subscribe(Subscriber, boolean)} or {@link #subscribe(Subscriber, Executor, boolean)} and
 * specify {@code true} for {@code withPooledObjects}. Note that the {@link Subscriber} is responsible for
 * releasing the objects given with {@link Subscriber#onNext(Object)}.
 *
 * @param <T> the type of element signaled
 */
public interface StreamMessage<T> extends Publisher<T> {
    /**
     * Returns {@code true} if this publisher is not closed yet.
     */
    boolean isOpen();

    /**
     * Returns {@code true} if this stream has been closed and did not publish any elements.
     * Note that this method will not return {@code true} when the stream is open even if it has not
     * published anything so far, because it may publish something later.
     */
    boolean isEmpty();

    /**
     * Returns a {@link CompletableFuture} that completes when this publisher is complete,
     * either successfully or exceptionally.
     */
    CompletableFuture<Void> closeFuture();

    /**
     * Requests to start streaming data to the specified {@link Subscriber}.
     *
     * @throws IllegalStateException if there is a {@link Subscriber} who subscribed to this stream already
     */
    @Override
    void subscribe(Subscriber<? super T> s);

    /**
     * Requests to start streaming data to the specified {@link Subscriber}.
     *
     * @param withPooledObjects if {@code true}, receives the pooled {@link ByteBuf} and {@link ByteBufHolder}
     *                          as is, without making a copy. If you don't know what this means, use
     *                          {@link StreamMessage#subscribe(Subscriber)}.
     * @throws IllegalStateException if there is a {@link Subscriber} who subscribed to this stream already
     */
    void subscribe(Subscriber<? super T> s, boolean withPooledObjects);

    /**
     * Requests to start streaming data, invoking the specified {@link Subscriber} from the specified
     * {@link Executor}.
     *
     * @throws IllegalStateException if there is a {@link Subscriber} who subscribed to this stream already
     */
    void subscribe(Subscriber<? super T> s, Executor executor);

    /**
     * Requests to start streaming data, invoking the specified {@link Subscriber} from the specified
     * {@link Executor}.
     *
     * @param withPooledObjects if {@code true}, receives the pooled {@link ByteBuf} and {@link ByteBufHolder}
     *                          as is, without making a copy. If you don't know what this means, use
     *                          {@link StreamMessage#subscribe(Subscriber)}.
     * @throws IllegalStateException if there is a {@link Subscriber} who subscribed to this stream already
     */
    void subscribe(Subscriber<? super T> s, Executor executor, boolean withPooledObjects);

    /**
     * Cancels the {@link Subscription} if any and closes this publisher.
     */
    void abort();
}
