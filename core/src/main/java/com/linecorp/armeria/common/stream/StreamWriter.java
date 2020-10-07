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

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.reactivestreams.Subscriber;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.HttpData;

/**
 * Produces the objects to be published by a {@link StreamMessage}.
 *
 * <h2 id="reference-counted">Life cycle of reference-counted objects</h2>
 *
 * <p>When the following methods are given with a pooled {@link HttpData} or the {@link Supplier} that
 * provides such an object:
 *
 * <ul>
 *   <li>{@link #tryWrite(Object)}</li>
 *   <li>{@link #tryWrite(Supplier)}</li>
 *   <li>{@link #write(Object)}</li>
 *   <li>{@link #write(Supplier)}</li>
 *   <li>{@link #close(Throwable)}</li>
 * </ul>
 * the object will be released automatically by the stream when it's no longer in use, such as when:
 * <ul>
 *   <li>The method returns {@code false} or raises an exception.</li>
 *   <li>The {@link Subscriber} of the stream consumes it.</li>
 *   <li>The stream is cancelled, aborted or failed.</li>
 * </ul>
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
     *
     * @throws ClosedStreamException if the stream was already closed
     * @throws IllegalArgumentException if the publication of the specified object has been rejected
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    default void write(T o) {
        if (!tryWrite(o)) {
            throw ClosedStreamException.get();
        }
    }

    /**
     * Writes the specified object {@link Supplier} to the {@link StreamMessage}. The object provided by the
     * {@link Supplier} will be transferred to the {@link Subscriber}.
     *
     * @throws ClosedStreamException if the stream was already closed.
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    default void write(Supplier<? extends T> o) {
        if (!tryWrite(o)) {
            throw ClosedStreamException.get();
        }
    }

    /**
     * Writes the specified object to the {@link StreamMessage}. The written object will be transferred to the
     * {@link Subscriber}.
     *
     * @return {@code true} if the specified object has been scheduled for publication. {@code false} if the
     *         stream has been closed already.
     *
     * @throws IllegalArgumentException if the publication of the specified object has been rejected
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    @CheckReturnValue
    boolean tryWrite(T o);

    /**
     * Writes the specified object {@link Supplier} to the {@link StreamMessage}. The object provided by the
     * {@link Supplier} will be transferred to the {@link Subscriber}.
     *
     * @return {@code true} if the specified object has been scheduled for publication. {@code false} if the
     *         stream has been closed already.
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    @CheckReturnValue
    default boolean tryWrite(Supplier<? extends T> o) {
        return tryWrite(o.get());
    }

    /**
     * Returns a {@link CompletableFuture} which is completed when all elements written so far have been
     * consumed by the {@link Subscriber}.
     *
     * @return the future which completes successfully when all elements written so far have been consumed,
     *         or exceptionally when the {@link StreamMessage} has been closed unexpectedly.
     */
    CompletableFuture<Void> whenConsumed();

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
