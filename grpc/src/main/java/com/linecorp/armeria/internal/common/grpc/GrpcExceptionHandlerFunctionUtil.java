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

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;

import io.grpc.Metadata;
import io.grpc.Status;

public final class GrpcExceptionHandlerFunctionUtil {

    public static Metadata generateMetadataFromThrowable(Throwable exception) {
        final Metadata metadata = Status.trailersFromThrowable(exception);
        return metadata != null ? metadata : new Metadata();
    }

    public static Status fromThrowable(RequestContext ctx, GrpcExceptionHandlerFunction exceptionHandler,
                                       Throwable t, Metadata metadata) {
        Status status = Status.fromThrowable(t);
        final Throwable cause = status.getCause();
        if (cause != null) {
            status = exceptionHandler.apply(ctx, status, cause, metadata);
        }
        assert status != null;
        return status;
    }

    private GrpcExceptionHandlerFunctionUtil() {}
}
