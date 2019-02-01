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
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.server.annotation.ProducesEventStream;
import com.linecorp.armeria.server.annotation.ServerSentEventResponseConverterFunction;

/**
 * An interface for the <a href="https://www.w3.org/TR/eventsource/">Server-Sent Events</a> specification.
 * If a {@link Publisher} or {@link Stream} produces objects which implement this interface, it can be
 * converted into a text event stream by {@link ServerSentEventResponseConverterFunction}.
 *
 * @see ProducesEventStream
 * @see ServerSentEventResponseConverterFunction
 */
public interface ServerSentEvent {

    /**
     * Returns a singleton empty {@link ServerSentEvent}.
     */
    static ServerSentEvent empty() {
        return DefaultServerSentEvent.EMPTY;
    }

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code id}.
     */
    static ServerSentEvent ofId(String id) {
        return new ServerSentEventBuilder().id(id).build();
    }

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code event}.
     */
    static ServerSentEvent ofEvent(String event) {
        return new ServerSentEventBuilder().event(event).build();
    }

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code retry}.
     */
    static ServerSentEvent ofRetry(Duration retry) {
        return new ServerSentEventBuilder().retry(retry).build();
    }

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code comment}.
     */
    static ServerSentEvent ofComment(String comment) {
        return new ServerSentEventBuilder().comment(comment).build();
    }

    /**
     * Creates a new {@link ServerSentEvent} with the specified {@code data}.
     */
    static ServerSentEvent ofData(String data) {
        return new ServerSentEventBuilder().data(data).build();
    }

    /**
     * Returns an ID of this event, if it exists. Otherwise, {@code null} will be returned.
     */
    @Nullable
    String id();

    /**
     * Returns an event name of this event, if it exists. Otherwise, {@code null} will be returned.
     */
    @Nullable
    String event();

    /**
     * Returns a reconnection time in milliseconds, if it exists. Otherwise, {@code null} will be returned.
     */
    @Nullable
    Duration retry();

    /**
     * Returns a comment of this event, if it exists. Otherwise, {@code null} will be returned.
     */
    @Nullable
    String comment();

    /**
     * Returns a data of this event, if it exists. Otherwise, {@code null} will be returned.
     */
    @Nullable
    String data();
}
