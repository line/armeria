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

package com.linecorp.armeria.internal.common.grpc;

import com.google.rpc.Status;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;

import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.protobuf.lite.ProtoLiteUtils;

/**
 * Common {@link Key}s that stored in a gRPC {@link Metadata}.
 */
@UnstableApi
public interface GrpcMetadataKeys {

    /**
     * A key for {@link Status} whose name is {@code "grpc-status-details-bin"}.
     */
    Key<Status> GRPC_STATUS_DETAILS_BIN_KEY = Key.of(
            GrpcHeaderNames.GRPC_STATUS_DETAILS_BIN.toString(),
            ProtoLiteUtils.metadataMarshaller(Status.getDefaultInstance()));
}
