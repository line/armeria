/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link Function} that determines the {@link LogLevel} of an {@link HttpRequest} from a given
 * {@link RequestOnlyLog}.
 *
 * @see LogWriterBuilder#requestLogLevelMapper(RequestLogLevelMapper)
 */
// TODO(trustin): Remove 'extends Function' in the next major release.
@UnstableApi
@FunctionalInterface
public interface RequestLogLevelMapper extends Function<RequestOnlyLog, LogLevel> {

    /**
     * Creates a new {@link RequestLogLevelMapper} which always returns the specified {@link LogLevel}.
     */
    static RequestLogLevelMapper of(LogLevel logLevel) {
        requireNonNull(logLevel, "logLevel");
        return log -> logLevel;
    }

    /**
     * Creates a new {@link RequestLogLevelMapper} which returns the specified {@link LogLevel} if the given
     * class of {@link RequestOnlyLog#requestCause()} is assignable from the specified {@link Throwable} class.
     */
    static RequestLogLevelMapper of(Class<? extends Throwable> clazz, LogLevel logLevel) {
        requireNonNull(clazz, "clazz");
        requireNonNull(logLevel, "logLevel");
        return log -> {
            final Throwable t = log.requestCause();
            if (t == null) {
                return null;
            }
            final Class<? extends Throwable> throwableClass = t.getClass();
            if (clazz.isAssignableFrom(throwableClass)) {
                return logLevel;
            }
            return null;
        };
    }

    /**
     * Returns a {@link LogLevel} for the given {@link RequestOnlyLog}. Returning {@code null} lets the next
     * handler specified with {@link #orElse(RequestLogLevelMapper)} map the {@link RequestOnlyLog}.
     */
    @Nullable
    @Override
    LogLevel apply(RequestOnlyLog log);

    /**
     * Returns a composed {@link RequestLogLevelMapper} which represents a logical OR of this
     * {@link RequestLogLevelMapper} and the given {@code other}.
     * If this {@link RequestLogLevelMapper#apply(RequestOnlyLog)} returns {@code null}, then the other
     * {@link RequestLogLevelMapper} will be applied.
     */
    default RequestLogLevelMapper orElse(RequestLogLevelMapper other) {
        requireNonNull(other, "other");
        if (this == other) {
            return this;
        }
        return log -> {
            final LogLevel logLevel = apply(log);
            if (logLevel != null) {
                return logLevel;
            }
            return other.apply(log);
        };
    }

    /**
     * Returns a composed function that first applies the before function to its input, and then applies this
     * function to the result. If evaluation of either function throws an exception, it is relayed to the
     * caller of the composed function.
     *
     * @deprecated Do not use this method.
     */
    @Deprecated
    @Override
    default <V> Function<V, LogLevel> compose(Function<? super V, ? extends RequestOnlyLog> before) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a composed function that first applies this function to its input, and then applies the after
     * function to the result. If evaluation of either function throws an exception, it is relayed to the
     * caller of the composed function.
     *
     * @deprecated Do not use this method.
     */
    @Deprecated
    @Override
    default <V> Function<RequestOnlyLog, V> andThen(Function<? super LogLevel, ? extends V> after) {
        throw new UnsupportedOperationException();
    }
}
