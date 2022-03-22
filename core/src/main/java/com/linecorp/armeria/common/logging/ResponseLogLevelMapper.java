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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link Function} that determines the {@link LogLevel} of an {@link HttpResponse} from a given
 * {@link RequestLog}.
 *
 * @see LoggingDecoratorBuilder#responseLogLevelMapper(ResponseLogLevelMapper)
 */
@FunctionalInterface
public interface ResponseLogLevelMapper extends Function<RequestLog, LogLevel> {

    /**
     * Returns the default {@link ResponseLogLevelMapper} which returns {@link LogLevel#DEBUG} when logging
     * successful responses (e.g., no unhandled exception) and {@link LogLevel#WARN} if failure.
     */
    static ResponseLogLevelMapper of() {
        return log -> log.responseCause() == null ? LogLevel.DEBUG : LogLevel.WARN;
    }

    /**
     * Creates a new {@link ResponseLogLevelMapper} which returns the specified {@link LogLevel} if the given
     * {@link RequestLog#responseStatus()} is equal to the specified {@link HttpStatus}.
     */
    static ResponseLogLevelMapper of(HttpStatus status, LogLevel logLevel) {
        requireNonNull(status, "status");
        requireNonNull(logLevel, "logLevel");
        return log -> log.responseStatus() == status ? logLevel : null;
    }

    /**
     * Creates a new {@link ResponseLogLevelMapper} which returns the specified {@link LogLevel} if the given
     * {@link RequestLog#responseStatus()} belongs to the specified {@link HttpStatusClass}.
     */
    static ResponseLogLevelMapper of(HttpStatusClass statusClass, LogLevel logLevel) {
        requireNonNull(statusClass, "statusClass");
        requireNonNull(logLevel, "logLevel");
        return log -> log.responseStatus().codeClass() == statusClass ? logLevel : null;
    }

    /**
     * Returns a {@link LogLevel} for the given {@link RequestLog}. Returning {@code null} lets the next handler
     * specified with {@link #orElse(ResponseLogLevelMapper)} map the {@link RequestLog}.
     */
    @Nullable
    @Override
    LogLevel apply(RequestLog log);

    /**
     * Returns a composed {@link ResponseLogLevelMapper} which represents a logical OR of this
     * {@link ResponseLogLevelMapper} and the given {@code other}.
     * If this {@link ResponseLogLevelMapper#apply(RequestLog)} returns {@code null}, then the other
     * {@link ResponseLogLevelMapper} will be applied.
     */
    default ResponseLogLevelMapper orElse(ResponseLogLevelMapper other) {
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
