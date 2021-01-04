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

package com.linecorp.armeria.common.multipart;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;

final class StreamMessages {

    /**
     * Concatenates an array of source {@link StreamMessage}s by relaying items
     * in order, non-overlappingly, one after the other finishes.
     */
    @SafeVarargs
    static <T> StreamMessage<T> concat(StreamMessage<? extends T>... streamMessages) {
        requireNonNull(streamMessages, "streamMessages");
        checkArgument(streamMessages.length > 0, "streamMessages is empty");

        return new ConcatArrayStreamMessage<>(streamMessages);
    }

    static <T> StreamMessage<? extends T> toStreamMessage(Publisher<? extends T> publisher) {
        if (publisher instanceof StreamMessage) {
            @SuppressWarnings("unchecked")
            final StreamMessage<? extends T> cast = (StreamMessage<? extends T>) publisher;
            return cast;
        } else {
            return new PublisherBasedStreamMessage<>(publisher);
        }
    }

    private StreamMessages() {}
}
