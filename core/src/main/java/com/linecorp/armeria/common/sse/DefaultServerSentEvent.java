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
import java.util.Objects;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

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

    private int hashCode;

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

    @Nullable
    @Override
    public String id() {
        return id;
    }

    @Nullable
    @Override
    public String event() {
        return event;
    }

    @Nullable
    @Override
    public Duration retry() {
        return retry;
    }

    @Nullable
    @Override
    public String comment() {
        return comment;
    }

    @Nullable
    @Override
    public T data() {
        return data;
    }

    @Nullable
    @Override
    public String dataText() {
        if (data == null) {
            return null;
        }

        final String dataText = this.dataText;
        if (dataText != null) {
            return dataText;
        }

        final String applied = dataStringifier.apply(data);
        this.dataText = applied;
        return applied;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            int hashCode = dataStringifier.hashCode();
            hashCode = 31 * hashCode + (id != null ? id.hashCode() : 0);
            hashCode = 31 * hashCode + (event != null ? event.hashCode() : 0);
            hashCode = 31 * hashCode + (retry != null ? retry.hashCode() : 0);
            hashCode = 31 * hashCode + (comment != null ? comment.hashCode() : 0);
            hashCode = 31 * hashCode + (data != null ? data.hashCode() : 0);
            this.hashCode = hashCode;
        }
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof DefaultServerSentEvent)) {
            return false;
        }

        final DefaultServerSentEvent<?> that = (DefaultServerSentEvent<?>) obj;
        return Objects.equals(dataStringifier, that.dataStringifier) &&
               Objects.equals(id, that.id) &&
               Objects.equals(event, that.event) &&
               Objects.equals(retry, that.retry) &&
               Objects.equals(comment, that.comment) &&
               Objects.equals(data, that.data);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("id", id)
                          .add("event", event)
                          .add("retry", retry)
                          .add("comment", comment)
                          .add("data", data)
                          .toString();
    }
}
