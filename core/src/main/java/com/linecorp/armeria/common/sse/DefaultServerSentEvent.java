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
import java.util.Optional;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * A default implementation of the {@link ServerSentEvent} interface.
 *
 * @param <T> the type of the data
 */
final class DefaultServerSentEvent<T> implements ServerSentEvent<T> {

    @Nullable
    private final String id;
    @Nullable
    private final String event;
    @Nullable
    private final Duration retry;
    @Nullable
    private final String comment;
    @Nullable
    private final T data;

    private final Function<T, String> dataStringifier;

    @Nullable
    private volatile String dataText;

    DefaultServerSentEvent(@Nullable String id,
                           @Nullable String event,
                           @Nullable Duration retry,
                           @Nullable String comment,
                           @Nullable T data,
                           Function<T, String> dataStringifier) {
        this.id = id;
        this.event = event;
        this.retry = retry;
        this.comment = comment;
        this.data = data;
        this.dataStringifier = requireNonNull(dataStringifier, "dataStringifier");
    }

    @Override
    public Optional<String> id() {
        return Optional.ofNullable(id);
    }

    @Override
    public Optional<String> event() {
        return Optional.ofNullable(event);
    }

    @Override
    public Optional<Duration> retry() {
        return Optional.ofNullable(retry);
    }

    @Override
    public Optional<String> comment() {
        return Optional.ofNullable(comment);
    }

    @Override
    public Optional<T> data() {
        return Optional.ofNullable(data);
    }

    @Override
    public Optional<String> dataText() {
        if (data == null) {
            return Optional.empty();
        }

        final String dataText = this.dataText;
        if (dataText != null) {
            return Optional.of(dataText);
        }

        final String applied = dataStringifier.apply(data);
        this.dataText = applied;
        return Optional.ofNullable(applied);
    }
}
