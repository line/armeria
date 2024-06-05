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
 */

package com.linecorp.armeria.internal.common.grpc;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.util.Exceptions;

import io.grpc.Metadata;
import io.grpc.Status;

public final class UnwrappingGrpcExceptionHandleFunction implements GrpcExceptionHandlerFunction {
    private final GrpcExceptionHandlerFunction delegate;

    public UnwrappingGrpcExceptionHandleFunction(GrpcExceptionHandlerFunction handlerFunction) {
        delegate = handlerFunction;
    }

    @Override
    public @Nullable Status apply(RequestContext ctx, Throwable cause, Metadata metadata) {
        final Throwable t = peelAndUnwrap(cause);
        return delegate.apply(ctx, t, metadata);
    }

    private static Throwable peelAndUnwrap(Throwable t) {
        requireNonNull(t, "t");
        Throwable cause = Exceptions.peel(t);
        while (cause != null) {
            if (cause instanceof ArmeriaStatusException) {
                return StatusExceptionConverter.toGrpc((ArmeriaStatusException) cause);
            }
            cause = cause.getCause();
        }
        return t;
    }
}
