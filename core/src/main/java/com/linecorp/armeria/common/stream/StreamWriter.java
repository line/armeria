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
import java.util.function.Supplier;

import org.reactivestreams.Subscriber;

/**
 * Produces the objects to be published by a {@link StreamMessage}.
 *
 * @param <T> the type of the stream element
 */
public interface StreamWriter<T> {

    /**
     * Returns {@code true} if the {@link StreamMessage} is open.
     */
    boolean isOpen();

    /**
     * Writes the specified object to the {@link StreamMessage}. The written object will be transferred to the
     * {@link Subscriber}.
     */
    boolean write(T o);

    /**
     * Writes the specified object {@link Supplier} to the {@link StreamMessage}. The object provided by the
     * {@link Supplier} will be transferred to the {@link Subscriber}.
     */
    boolean write(Supplier<? extends T> o);

    /**
     * Performs the specified {@code task} when there's enough demans from the {@link Subscriber}.
     *
     * @return the future that completes successfully when the {@code task} finishes or
     *         exceptionally when the {@link StreamMessage} is closed unexpectedly.
     */
    CompletableFuture<Void> onDemand(Runnable task);

    /**
     * Closes the {@link StreamMessage} successfully. {@link Subscriber#onComplete()} will be invoked to
     * signal that the {@link Subscriber} has consumed the stream completely.
     */
    void close();

    /**
     * Closes the {@link StreamMessage} exceptionally. {@link Subscriber#onError(Throwable)} will be invoked to
     * signal that the {@link Subscriber} did not consume the stream completely.
     */
    void close(Throwable cause);
}
