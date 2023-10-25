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

import static com.linecorp.armeria.common.grpc.GoogleGrpcExceptionHandlerFunctionUtil.handleException;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

/**
 * A {@link GoogleGrpcExceptionHandlerFunction} that provides a way to include details of a status into
 * a {@link Metadata}. You can implement a mapping function to convert {@link Throwable} into
 * a {@link com.google.rpc.Status} which is stored in the `grpc-status-details-bin` key in the {@link Metadata}.
 * If a given {@link Throwable} is an instance of either {@link StatusRuntimeException} or
 * {@link StatusException}, the {@link Status} retrieved from the exception is
 * returned with higher priority.
 */
@UnstableApi
public interface GoogleGrpcExceptionHandlerFunction extends GrpcExceptionHandlerFunction {

    @Nullable
    @Override
    default Status apply(RequestContext ctx, Throwable throwable, Metadata metadata) {
        return handleException(ctx, throwable, metadata, this::applyStatusProto);
    }

    /**
     * Maps the specified {@link Throwable} to a {@link com.google.rpc.Status},
     * and mutates the specified {@link Metadata}.
     * The `grpc-status-details-bin` key is ignored since it will be overwritten
     * by {@link GoogleGrpcExceptionHandlerFunction#apply(RequestContext, Throwable, Metadata)}.
     * If {@code null} is returned, the built-in mapping rule is used by default.
     */
    com.google.rpc.@Nullable Status applyStatusProto(RequestContext ctx, Throwable throwable,
                                                     Metadata metadata);
}
