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
/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

/**
 * Represents a {@link Publisher} emitting zero or more items, optionally followed by
 * an error or completion.
 *
 * @param <T> item type
 */
interface Multi<T> extends Publisher<T> {

    // Forked https://github.com/oracle/helidon/blob/9d209a1a55f927e60e15b061700384e438ab5a01/common/reactive/src/main/java/io/helidon/common/reactive/Multi.java
    // - Replaced Flow.* with reactivestreams.*
    // - Removed unnecessary operators

    // --------------------------------------------------------------------------------------------------------
    // Factory (source-like) methods
    // --------------------------------------------------------------------------------------------------------

    /**
     * Concat streams to one.
     *
     * @param firstMulti  first stream
     * @param secondMulti second stream
     * @param <T>         item type
     */
    static <T> Multi<T> concat(Publisher<? extends  T> firstMulti, Publisher<? extends T> secondMulti) {
        return new ConcatPublisher<>(firstMulti, secondMulti);
    }

    /**
     * Concatenates an array of source {@link Publisher}s by relaying items
     * in order, non-overlappingly, one after the other finishes.
     * @param publishers more publishers to concat
     * @param <T> item type
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    static <T> Multi<T> concatArray(Publisher<? extends T>... publishers) {
        requireNonNull(publishers, "publishers");
        if (publishers.length == 0) {
            return empty();
        } else if (publishers.length == 1) {
            return from(publishers[0]);
        }
        return new MultiConcatArray<>(publishers);
    }

    /**
     * Returns a {@link Multi} instance that completes immediately.
     *
     * @param <T> item type
     */
    static <T> Multi<T> empty() {
        return MultiEmpty.instance();
    }

    /**
     * Returns a {@link Multi} instance that reports the given exception to its subscriber(s).
     * The exception is reported by invoking {@link Subscriber#onError(Throwable)}
     * when {@link Publisher#subscribe(Subscriber)} is called.
     *
     * @param <T>   item type
     * @param error exception to hold
     */
    static <T> Multi<T> error(Throwable error) {
        return new MultiError<>(error);
    }

    /**
     * Returns a {@link Multi} instance wrapped around the given {@link Publisher}.
     *
     * @param <T> item type
     * @param source source publisher
     */
    @SuppressWarnings("unchecked")
    static <T> Multi<T> from(Publisher<? extends T> source) {
        if (source instanceof Multi) {
            return (Multi<T>) source;
        }
        return new MultiFromPublisher<>(source);
    }

    /**
     * Returns a {@link Multi} instance wrapped around the given {@link StreamMessage}.
     *
     * @param <T> item type
     * @param source source publisher
     */
    static <T> Multi<T> from(StreamMessage<? extends T> source, SubscriptionOption... options) {
        return new MultiFromStreamMessage<>(source, options);
    }

    /**
     * Returns a {@link Multi} instance that publishes the given {@link Iterable}.
     *
     * @param <T> item type
     * @param iterable iterable to publish
     */
    static <T> Multi<T> from(Iterable<? extends T> iterable) {
        return new MultiFromIterable<>(iterable);
    }

    /**
     * Returns a {@link Multi} instance that publishes the given items to a single subscriber.
     *
     * @param <T>   item type
     * @param items items to publish
     */
    @SafeVarargs
    static <T> Multi<T> just(T... items) {
        requireNonNull(items, "items");
        if (items.length == 0) {
            return empty();
        }
        if (items.length == 1) {
            return singleton(items[0]);
        }
        return new MultiFromArrayPublisher<>(items);
    }

    /**
     * Returns a {@link Multi} that emits a pre-existing item and then completes.
     *
     * @param item the item to emit.
     * @param <T> the type of the item
     */
    static <T> Multi<T> singleton(T item) {
        return new MultiJustPublisher<>(item);
    }

    /**
     * Transforms item with supplied function and flatten resulting {@link Publisher} to downstream.
     *
     * @param mapper {@link Function} receiving item as parameter and returning {@link Publisher}
     * @param <U> output item type
     */
    default <U> Multi<U> flatMap(Function<? super T, ? extends Publisher<? extends U>> mapper) {
        return new MultiFlatMapPublisher<>(this, mapper, 32, 32, false);
    }

    /**
     * Transforms this {@link Multi} instance to a new {@link Multi} of another type using
     * the given {@link Function}.
     *
     * @param <U> mapped item type
     * @param mapper mapper
     */
    default <U> Multi<U> map(Function<? super T, ? extends U> mapper) {
        return new MultiMapperPublisher<>(this, mapper);
    }

    /**
     * Filters stream items with provided predicate.
     *
     * @param predicate predicate to filter stream with
     */
    default Multi<T> filter(Predicate<? super T> predicate) {
        return new MultiFilterPublisher<>(this, predicate);
    }

    /**
     * Resumes stream from single item if onComplete signal is intercepted. Effectively do an {@code append}
     * to the stream.
     *
     * @param item one item to resume stream with
     */
    default Multi<T> onCompleteResume(T item) {
        return onCompleteResumeWith(singleton(item));
    }

    /**
     * Resumes stream from supplied publisher if onComplete signal is intercepted.
     *
     * @param publisher new stream publisher
     */
    default Multi<T> onCompleteResumeWith(Publisher<? extends T> publisher) {
        return new MultiOnCompleteResumeWith<>(this, publisher);
    }
}
