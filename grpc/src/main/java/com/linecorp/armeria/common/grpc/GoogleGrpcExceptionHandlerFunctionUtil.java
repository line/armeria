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

import static com.linecorp.armeria.internal.common.grpc.MetadataUtil.GRPC_STATUS_DETAILS_BIN_KEY;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

final class GoogleGrpcExceptionHandlerFunctionUtil {

    // TODO(ikhoon): Remove this class when we remove GoogleGrpcStatusFunction.

    @Nullable
    static Status handleException(RequestContext ctx, Throwable throwable, Metadata metadata,
                                  StatusProtoHandler handler) {
        final Throwable cause = Exceptions.peel(requireNonNull(throwable, "throwable"));
        if (cause instanceof StatusRuntimeException) {
            return ((StatusRuntimeException) cause).getStatus();
        }
        if (cause instanceof StatusException) {
            return ((StatusException) cause).getStatus();
        }
        final com.google.rpc.Status statusProto = handler.applyStatusProto(ctx, cause, metadata);
        if (statusProto == null) {
            return null;
        }
        final Status status = Status.fromCodeValue(statusProto.getCode())
                                    .withDescription(statusProto.getMessage());
        metadata.discardAll(GRPC_STATUS_DETAILS_BIN_KEY);
        metadata.put(GRPC_STATUS_DETAILS_BIN_KEY, statusProto);
        return status;
    }

    @FunctionalInterface
    interface StatusProtoHandler {
        com.google.rpc.@Nullable Status applyStatusProto(RequestContext ctx, Throwable throwable,
                                                         Metadata metadata);
    }

    private GoogleGrpcExceptionHandlerFunctionUtil() {}
}
