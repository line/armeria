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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * A builder which creates a {@link ServerSentEvent} instance.
 */
public class ServerSentEventBuilder<T> {

    @Nullable
    private String id;
    @Nullable
    private String event;
    @Nullable
    private Duration retry;
    @Nullable
    private String comment;
    @Nullable
    private T data;
    private Function<T, String> dataStringifier = String::valueOf;

    /**
     * Sets the specified {@code id}.
     */
    public ServerSentEventBuilder<T> id(String id) {
        this.id = requireNonNull(id, "id");
        return this;
    }

    /**
     * Sets the specified {@code event}.
     */
    public ServerSentEventBuilder<T> event(String event) {
        this.event = requireNonNull(event, "event");
        return this;
    }

    /**
     * Sets the specified {@code retry}.
     */
    public ServerSentEventBuilder<T> retry(Duration retry) {
        this.retry = requireNonNull(retry, "retry");
        return this;
    }

    /**
     * Sets the specified {@code comment}.
     */
    public ServerSentEventBuilder<T> comment(String comment) {
        this.comment = requireNonNull(comment, "comment");
        return this;
    }

    /**
     * Sets the specified {@code data}.
     */
    public ServerSentEventBuilder<T> data(T data) {
        this.data = requireNonNull(data, "data");
        return this;
    }

    /**
     * Sets the specified {@code dataStringifier} which can encode a data object as a {@link String}.
     * If unspecified, {@link String#valueOf(Object)} will be used by default.
     */
    public ServerSentEventBuilder<T> dataStringifier(Function<T, String> dataStringifier) {
        this.dataStringifier = requireNonNull(dataStringifier, "dataStringifier");
        return this;
    }

    /**
     * Creates a new {@link ServerSentEvent} instance.
     */
    public ServerSentEvent<T> build() {
        return new DefaultServerSentEvent<>(id, event, retry, comment, data, dataStringifier);
    }
}
