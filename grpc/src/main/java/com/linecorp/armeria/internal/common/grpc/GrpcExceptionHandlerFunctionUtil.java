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
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.util.Exceptions;

import io.grpc.Metadata;
import io.grpc.Status;

public final class GrpcExceptionHandlerFunctionUtil {

    public static Metadata generateMetadataFromThrowable(Throwable exception) {
        final Metadata metadata = Status.trailersFromThrowable(peelAndUnwrap(exception));
        return metadata != null ? metadata : new Metadata();
    }

    public static Status fromThrowable(RequestContext ctx, GrpcExceptionHandlerFunction exceptionHandler,
                                       Throwable t, Metadata metadata) {
        final Status status = Status.fromThrowable(peelAndUnwrap(t));
        final Throwable cause = status.getCause();
        if (cause == null) {
            return status;
        }
        return applyExceptionHandler(ctx, exceptionHandler, status, cause, metadata);
    }

    public static Status applyExceptionHandler(RequestContext ctx,
                                               GrpcExceptionHandlerFunction exceptionHandler,
                                               Status status, Throwable cause, Metadata metadata) {
        final Throwable peeled = peelAndUnwrap(cause);
        status = exceptionHandler.apply(ctx, status, peeled, metadata);
        assert status != null;
        return status;
    }

    private static Throwable peelAndUnwrap(Throwable t) {
        requireNonNull(t, "t");
        t = Exceptions.peel(t);
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof ArmeriaStatusException) {
                return StatusExceptionConverter.toGrpc((ArmeriaStatusException) cause);
            }
            cause = cause.getCause();
        }
        return t;
    }

    private GrpcExceptionHandlerFunctionUtil() {}
}
