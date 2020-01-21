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

/**
 * A duplicator that duplicates a {@link StreamMessage} into one or more {@link StreamMessage}s,
 * which publish the same elements.
 *
 * <p>Only one subscriber can subscribe to a {@link StreamMessage}. If you want to subscribe to it
 * multiple times, use {@link StreamMessageDuplicator} which is returned by calling
 * {@link StreamMessage#toDuplicator()}.
 * <pre>{@code
 * StreamMessage<String> streamMessage = ...
 * StreamMessageDuplicator<String> duplicator = streamMessage.toDuplicator();
 * // streamMessage.subscribe(...) will throw an exception. You cannot subscribe to streamMessage anymore.
 *
 * // Duplicate the stream as many as you want to subscribe.
 * StreamMessage<String> duplicatedStreamMessage1 = duplicator.duplicate();
 * StreamMessage<String> duplicatedStreamMessage2 = duplicator.duplicate();
 * duplicatedStreamMessage1.subscribe(...);
 * duplicatedStreamMessage2.subscribe(...);
 *
 * duplicator.close(); // You should call close to clean up the resources.
 * }</pre>
 *
 * <p>If you subscribe to the {@linkplain #duplicate() duplicated stream message} with the
 * {@link SubscriptionOption#WITH_POOLED_OBJECTS}, the published elements can be shared across
 * {@link Subscriber}s. So do not manipulate the data unless you copy them.
 *
 * <p>To clean up the resources, you have to call one of {@linkplain #duplicate(boolean) duplicate(true)},
 * {@link #close()} or {@link #abort()}. Otherwise, memory leak might happen.</p>
 *
 * @param <T> the type of elements
 */
public interface StreamMessageDuplicator<T> {

    /**
     * Returns a new {@link StreamMessage} that publishes the same elements with the {@link StreamMessage}
     * that this duplicator is created from.
     *
     * @param lastStream whether to prevent further duplication
     */
    StreamMessage<T> duplicate(boolean lastStream);

    /**
     * Returns a new {@link StreamMessage} that publishes the same elements with the {@link StreamMessage}
     * that this duplicator is created from.
     */
    default StreamMessage<T> duplicate() {
        return duplicate(false);
    }

    /**
     * Closes this duplicator and prevents it from further duplication. {@link #duplicate()} will raise
     * an {@link IllegalStateException} after this method is invoked.
     * Note that the previously {@linkplain #duplicate() duplicated streams} will not be closed but will
     * continue publishing data until the original {@link StreamMessage} is closed.
     * All the data published from the original {@link StreamMessage} are cleaned up when
     * all {@linkplain #duplicate() duplicated streams} are complete.
     */
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
