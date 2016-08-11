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

package com.linecorp.armeria.common.reactivestreams;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A <a href="http://www.reactive-streams.org/">Reactive Streams</a> {@link Publisher} with extra functionality.
 *
 * @param <T> the type of element signaled
 */
public interface RichPublisher<T> extends Publisher<T> {
    /**
     * Returns {@code true} if this publisher is not terminated yet.
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
     * Request {@link Publisher} to start streaming data, invoking the specified {@link Subscriber} from
     * the specified {@link Executor}.
     */
    void subscribe(Subscriber<? super T> s, Executor executor);

    /**
     * Cancels all {@link Subscription}s and terminates this publisher.
     */
    void abort();
}
