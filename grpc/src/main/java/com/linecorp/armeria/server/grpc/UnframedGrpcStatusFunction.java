/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Status;

/**
 * A mapping function that converts a {@link Throwable} or gRPC {@link Status} into an {@link HttpStatus}.
 */
@FunctionalInterface
public interface UnframedGrpcStatusFunction {

    /**
     * Return the default mapping function which follows the mapping rules defined in upstream Google APIs.
     */
    static UnframedGrpcStatusFunction of() {
        return (ctx, status, response) -> GrpcStatus.grpcStatusToHttpStatus(status);
    }

    /**
     * Maps the specified {@link Throwable} or gRPC {@link Status} to an {@link HttpStatus}.
     */
    @Nullable
    HttpStatus apply(ServiceRequestContext ctx, Status status, @Nullable Throwable cause);

    /**
     * If {@link #apply(ServiceRequestContext, Status, Throwable)} returns {@code null}, the {@code other}
     * will be returned as default.
     */
    default UnframedGrpcStatusFunction orElse(UnframedGrpcStatusFunction other) {
        return (ctx, status, cause) -> {
            final HttpStatus httpStatus = apply(ctx, status, cause);
            if (httpStatus != null) {
                return httpStatus;
            }
            return other.apply(ctx, status, cause);
        };
    }
}
