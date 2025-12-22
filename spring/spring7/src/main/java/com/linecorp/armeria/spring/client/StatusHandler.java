/*
 * Copyright 2024 LINE Corporation
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
 *
 */

package com.linecorp.armeria.spring.client;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A handler that converts specific error {@link HttpStatus}s to a {@link Throwable} to be propagated
 * downstream instead of the response.
 */
@UnstableApi
@FunctionalInterface
public interface StatusHandler {

    /**
     * Returns a new {@link StatusHandler} that converts the specified {@link HttpStatus} to
     * a {@link Throwable}.
     */
    static StatusHandler of(HttpStatus status,
                            Function<? super ClientRequestContext, ? extends Throwable> errorFunction) {
        requireNonNull(status, "status");
        requireNonNull(errorFunction, "errorFunction");
        return (ctx, s) -> s.equals(status) ? errorFunction.apply(ctx) : null;
    }

    /**
     * Converts the specified {@link HttpStatus} to a {@link Throwable}.
     * If the {@link HttpStatus} is not handled by this handler, it must return {@code null}.
     */
    @Nullable
    Throwable handle(ClientRequestContext ctx, HttpStatus status);

    /**
     * Returns a new {@link StatusHandler} that tries this handler first and then the specified {@code other}
     * handler if this handler does not handle the specified {@link HttpStatus}.
     */
    default StatusHandler orElse(StatusHandler other) {
        requireNonNull(other, "other");
        return (ctx, status) -> {
            final Throwable cause = handle(ctx, status);
            return cause != null ? cause : other.handle(ctx, status);
        };
    }
}
