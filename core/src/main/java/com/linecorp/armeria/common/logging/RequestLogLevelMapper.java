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

/**
 * A {@link Function} that determines the {@link LogLevel} of an {@link HttpRequest} from a given
 * {@link RequestOnlyLog}.
 *
 * @see LoggingDecoratorBuilder#requestLogLevelMapper(RequestLogLevelMapper)
 */
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
     * Returns a {@link LogLevel} for the given {@link RequestOnlyLog}. Returning {@code null} lets the next handler
     * specified with {@link #orElse(RequestLogLevelMapper)} map the {@link RequestOnlyLog}.
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
}
