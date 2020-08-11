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

import java.util.function.Predicate;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;

final class StreamMessages {

    /**
     * Concatenates an array of source {@link StreamMessage}s by relaying items
     * in order, non-overlappingly, one after the other finishes.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    static <T> StreamMessage<T> concat(Publisher<? extends T>... publishers) {
        requireNonNull(publishers, "publishers");
        checkArgument(publishers.length > 0, "publishers is empty");

        @SuppressWarnings("unchecked")
        final StreamMessage<? extends T>[] streamMessages = new StreamMessage[publishers.length];
        for (int i = 0; i < publishers.length; i++) {
            streamMessages[i] = toStreamMessage(publishers[i]);
        }

        return new ConcatArrayStreamMessage<>(streamMessages);
    }

    /**
     * Concatenates a {@link StreamMessage} of {@link StreamMessage} to one.
     */
    static <T> StreamMessage<T> concat(Publisher<? extends Publisher<? extends T>> publishers) {
        return new ConcatPublisherStreamMessage<>(toStreamMessage(publishers));
    }

    /**
     * Resumes stream from supplied publisher if onComplete signal is intercepted.
     *
     * @param publisher new stream publisher
     */
    static <T> StreamMessage<T> onCompleteResumeWith(Publisher<? extends T> source,
                                                     Publisher<? extends T> publisher) {
        return new StreamMessageOnCompleteResumeWith<>(toStreamMessage(source), toStreamMessage(publisher));
    }

    /**
     * Evaluates each source value against the specified {@link Predicate}.
     * If the {@link Predicate} test succeeds, the value is emitted.
     * If the {@link Predicate} test fails, the value is ignored and a request of {@code 1} is made upstream.
     */
    static <T> StreamMessage<T> filter(Publisher<? extends T> source, Predicate<? super T> predicate) {
        final StreamMessage<? extends T> streamMessage = toStreamMessage(source);
        return new StreamMessageFilter<>(streamMessage, predicate);
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
