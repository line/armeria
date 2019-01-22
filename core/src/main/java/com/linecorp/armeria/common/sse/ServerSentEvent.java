/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common.sse;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.server.annotation.ProducesEventStream;
import com.linecorp.armeria.server.annotation.ServerSentEventResponseConverterFunction;

/**
 * An interface for the <a href="https://www.w3.org/TR/eventsource/">Server-sent Event</a> specification.
 * If a {@link Publisher} or {@link Stream} produces objects which implement this interface, it can be
 * converted into a text event stream by {@link ServerSentEventResponseConverterFunction}.
 *
 * @param <T> the type of the data
 *
 * @see ProducesEventStream
 * @see ServerSentEventResponseConverterFunction
 */
public interface ServerSentEvent<T> {

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code id}.
     */
    static <T> ServerSentEvent<T> ofId(String id) {
        return new ServerSentEventBuilder<T>().id(id).build();
    }

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code event}.
     */
    static <T> ServerSentEvent<T> ofEvent(String event) {
        return new ServerSentEventBuilder<T>().event(event).build();
    }

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code retry}.
     */
    static <T> ServerSentEvent<T> ofRetry(Duration retry) {
        return new ServerSentEventBuilder<T>().retry(retry).build();
    }

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code comment}.
     */
    static <T> ServerSentEvent<T> ofComment(String comment) {
        return new ServerSentEventBuilder<T>().comment(comment).build();
    }

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code data} and {@code stringifier}.
     */
    static <T> ServerSentEvent<T> ofData(T data, Function<T, String> stringifier) {
        return new ServerSentEventBuilder<T>().data(data).dataStringifier(stringifier).build();
    }

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code data}.
     */
    static ServerSentEvent<String> ofData(String data) {
        return new ServerSentEventBuilder<String>().data(data).build();
    }

    /**
     * Returns an ID of this event, if it exists. Otherwise, {@link Optional#empty()} will be returned.
     */
    Optional<String> id();

    /**
     * Returns an event name of this event, if it exists. Otherwise, {@link Optional#empty()} will be returned.
     */
    Optional<String> event();

    /**
     * Returns a reconnection time in milliseconds, if it exists. Otherwise, {@link Optional#empty()} will
     * be returned.
     */
    Optional<Duration> retry();

    /**
     * Returns a comment of this event, if it exists. Otherwise, {@link Optional#empty()} will be returned.
     */
    Optional<String> comment();

    /**
     * Returns a data of this event, if it exists. Otherwise, {@link Optional#empty()} will be returned.
     */
    Optional<T> data();

    /**
     * Returns a data of this event as a UTF-8 string, if it exists. Otherwise, {@link Optional#empty()} will
     * be returned.
     */
    Optional<String> dataText();
}
