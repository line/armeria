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
     * Creates a new {@link ResponseLogLevelMapper} which returns the specified {@link LogLevel} if the given
     * {@link RequestLog}'s status is equal to the specified {@link HttpStatus}.
     */
    static ResponseLogLevelMapper of(HttpStatus status, LogLevel logLevel) {
        return new HttpStatusResponseLogLevelMapper(status, logLevel);
    }

    /**
     * Creates a new {@link ResponseLogLevelMapper} which returns the specified {@link LogLevel} if the given
     * {@link RequestLog}'s status belongs to the specified {@link HttpStatusClass}.
     */
    static ResponseLogLevelMapper of(HttpStatusClass statusClass, LogLevel logLevel) {
        return new HttpStatusClassResponseLogLevelMapper(statusClass, logLevel);
    }

    /**
     * Returns a {@link LogLevel} for the given {@link RequestLog}.
     */
    @Nullable
    @Override
    LogLevel apply(RequestLog requestLog);

    /**
     * Returns a composed {@link ResponseLogLevelMapper} which represents a logical OR of this
     * {@link ResponseLogLevelMapper} and the given {@code other}.
     * If this {@link ResponseLogLevelMapper#apply(RequestLog)} returns {@code null}, then the other
     * {@link ResponseLogLevelMapper} will be applied.
     */
    default ResponseLogLevelMapper orElse(ResponseLogLevelMapper other) {
        requireNonNull(other, "other");
        return requestLog -> {
            final LogLevel logLevel = ResponseLogLevelMapper.this.apply(requestLog);
            if (logLevel != null) {
                return logLevel;
            }
            return other.apply(requestLog);
        };
    }
}
