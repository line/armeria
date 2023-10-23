/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.Metadata;
import io.grpc.Status;

/**
 * A builder for {@link GrpcExceptionHandlerFunction}.
 */
@UnstableApi
public final class GrpcExceptionHandlerFunctionBuilder {

    @VisibleForTesting
    final LinkedList<Entry<Class<? extends Throwable>, GrpcExceptionHandlerFunction>> exceptionMappings =
            new LinkedList<>();

    GrpcExceptionHandlerFunctionBuilder() {}

    /**
     * Adds the specified exception mapping that maps a {@link Throwable} to a gRPC {@link Status}.
     * The mapping is used to handle a {@link Throwable} when it is raised.
     */
    public GrpcExceptionHandlerFunctionBuilder on(Class<? extends Throwable> exceptionType, Status status) {
        requireNonNull(status, "status");
        return on(exceptionType, (ctx, cause, metadata) -> status);
    }

    /**
     * Adds the specified exception mapping that maps a {@link Throwable} to a gRPC {@link Status}.
     * The mapping is used to handle a {@link Throwable} when it is raised.
     */
    public <T extends Throwable> GrpcExceptionHandlerFunctionBuilder on(
            Class<T> exceptionType, BiFunction<T, Metadata, Status> exceptionHandler) {
        requireNonNull(exceptionType, "exceptionType");
        requireNonNull(exceptionHandler, "exceptionHandler");
        //noinspection unchecked
        return on(exceptionType, (ctx, cause, metadata) -> exceptionHandler.apply((T) cause, metadata));
    }

    /**
     * Adds the specified exception mapping that maps a {@link Throwable} to a gRPC {@link Status}.
     * The mapping is used to handle a {@link Throwable} when it is raised.
     */
    public GrpcExceptionHandlerFunctionBuilder on(Class<? extends Throwable> exceptionType,
                                                  GrpcExceptionHandlerFunction exceptionHandler) {
        requireNonNull(exceptionType, "exceptionType");
        requireNonNull(exceptionHandler, "exceptionHandler");

        final ListIterator<Entry<Class<? extends Throwable>, GrpcExceptionHandlerFunction>> it =
                exceptionMappings.listIterator();

        while (it.hasNext()) {
            final Map.Entry<Class<? extends Throwable>, GrpcExceptionHandlerFunction> next = it.next();
            final Class<? extends Throwable> oldExceptionType = next.getKey();
            checkArgument(oldExceptionType != exceptionType, "%s is already added with %s",
                          oldExceptionType, next.getValue());

            if (oldExceptionType.isAssignableFrom(exceptionType)) {
                // exceptionType is a subtype of oldExceptionType. exceptionType needs a higher priority.
                it.previous();
                it.add(Maps.immutableEntry(exceptionType, exceptionHandler));
                return this;
            }
        }

        exceptionMappings.add(Maps.immutableEntry(exceptionType, exceptionHandler));
        return this;
    }

    /**
     * Returns a newly created {@link GrpcExceptionHandlerFunction} based on the mappings added to this builder.
     */
    public GrpcExceptionHandlerFunction build() {
        checkState(!exceptionMappings.isEmpty(), "no exception handler is added.");

        final List<Entry<Class<? extends Throwable>, GrpcExceptionHandlerFunction>> mappings =
                ImmutableList.copyOf(exceptionMappings);
        return (ctx, cause, metadata) -> {
            for (Map.Entry<Class<? extends Throwable>, GrpcExceptionHandlerFunction> mapping : mappings) {
                if (mapping.getKey().isInstance(cause)) {
                    final Status status = mapping.getValue().apply(ctx, cause, metadata);
                    return status == null ? null : status.withCause(cause);
                }
            }
            return null;
        };
    }
}
