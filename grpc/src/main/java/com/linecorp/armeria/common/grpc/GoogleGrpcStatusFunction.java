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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;

import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.lite.ProtoLiteUtils;

/**
 * A {@link GrpcStatusFunction} that provides a way to include details of a status into a {@link Metadata}.
 * The details are stored in the `grpc-status-details-bin` key in the {@link Metadata}
 * as a {@link com.google.rpc.Status}.
 * You can implement a mapping function to converts {@link Throwable} into a {@link com.google.rpc.Status}
 * which is stored in the `grpc-status-details-bin` key in the {@link Metadata}.
 * If a given {@link Throwable} is an instance of either {@link StatusRuntimeException} or
 * {@link StatusException}, returns a {@link Status} retrieved from the exception
 * prior to the mapping function you implemented.
 */
@UnstableApi
public interface GoogleGrpcStatusFunction extends GrpcStatusFunction {

    Key<com.google.rpc.Status> GRPC_STATUS_DETAILS_BIN_KEY = Key.of(
            "grpc-status-details-bin",
            ProtoLiteUtils.metadataMarshaller(com.google.rpc.Status.getDefaultInstance()));

    @Nullable
    @Override
    default Status apply(RequestContext ctx, Throwable throwable, Metadata metadata) {
        final Throwable cause = Exceptions.peel(requireNonNull(throwable, "throwable"));
        if (cause instanceof StatusRuntimeException) {
            return ((StatusRuntimeException) cause).getStatus();
        }
        if (cause instanceof StatusException) {
            return ((StatusException) cause).getStatus();
        }
        final com.google.rpc.Status statusProto = applyStatusProto(ctx, cause, metadata);
        if (statusProto == null) {
            return null;
        }
        final Status status = Status.fromCodeValue(statusProto.getCode())
                                    .withDescription(statusProto.getMessage());
        metadata.discardAll(GRPC_STATUS_DETAILS_BIN_KEY);
        metadata.put(GRPC_STATUS_DETAILS_BIN_KEY, statusProto);
        return status;
    }

    /**
     * Maps the specified {@link Throwable} to a {@link com.google.rpc.Status},
     * and mutates the specified {@link Metadata}.
     * The `grpc-status-details-bin` key is ignored since it will be overwritten
     * by {@link GoogleGrpcStatusFunction#apply(RequestContext, Throwable, Metadata)}.
     * If {@code null} is returned, the built-in mapping rule is used by default.
     */
    com.google.rpc.Status applyStatusProto(RequestContext ctx, Throwable throwable, Metadata metadata);
}
