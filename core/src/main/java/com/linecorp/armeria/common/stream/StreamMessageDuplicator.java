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

package com.linecorp.armeria.common.stream;

import org.reactivestreams.Subscriber;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * A duplicator that duplicates a {@link StreamMessage} into one or more {@link StreamMessage}s,
 * which publish the same elements.
 *
 * <p>Only one subscriber can subscribe to a {@link StreamMessage}. If you want to subscribe to it
 * multiple times, use {@link StreamMessageDuplicator} which is returned by calling
 * {@link StreamMessage#toDuplicator()}.
 * <pre>{@code
 * StreamMessage<String> streamMessage = ...
 * try (StreamMessageDuplicator<String> duplicator = streamMessage.toDuplicator()) {
 *     // streamMessage.subscribe(...) will throw an exception. You cannot subscribe to streamMessage anymore.
 *
 *     // Duplicate the stream as many as you want to subscribe.
 *     StreamMessage<String> duplicatedStreamMessage1 = duplicator.duplicate();
 *     StreamMessage<String> duplicatedStreamMessage2 = duplicator.duplicate();
 *
 *     duplicatedStreamMessage1.subscribe(...);
 *     duplicatedStreamMessage2.subscribe(...);
 * }
 * }</pre>
 *
 * <p>Use the {@code try-with-resources} block or call {@link #close()} manually to clean up the resources
 * after all subscriptions are done. If you want to stop publishing and clean up the resources immediately,
 * call {@link #abort()}. If you do none of these, memory leak might happen.</p>
 *
 * <p>If you subscribe to the {@linkplain #duplicate() duplicated stream message} with the
 * {@link SubscriptionOption#WITH_POOLED_OBJECTS}, the published elements can be shared across
 * {@link Subscriber}s. So do not manipulate the data unless you copy them.
 *
 * @param <T> the type of elements
 */
public interface StreamMessageDuplicator<T> extends SafeCloseable {

    /**
     * Returns a new {@link StreamMessage} that publishes the same elements as the {@link StreamMessage}
     * that this duplicator is created from.
     */
    @CheckReturnValue
    StreamMessage<T> duplicate();

    /**
     * Closes this duplicator and prevents it from further duplication. {@link #duplicate()} will raise
     * an {@link IllegalStateException} after this method is invoked.
     *
     * <p>Note that the previously {@linkplain #duplicate() duplicated streams} will not be closed but will
     * continue publishing data until the original {@link StreamMessage} is closed.
     * All the data published from the original {@link StreamMessage} are cleaned up when
     * all {@linkplain #duplicate() duplicated streams} are complete. If you want to stop publishing and clean
     * up the resources immediately, call {@link #abort()}.
     */
    @Override
    void close();

    /**
     * Closes this duplicator and aborts all stream messages returned by {@link #duplicate()}.
     * This will also clean up the data published from the original {@link StreamMessage}.
     */
    void abort();

    /**
     * Closes this duplicator and aborts all stream messages returned by {@link #duplicate()}
     * with the specified {@link Throwable}.
     * This will also clean up the data published from the original {@link StreamMessage}.
     */
    void abort(Throwable cause);
}
