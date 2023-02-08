/*
 * Copyright 2017 LINE Corporation
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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;

import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Utilities shared between server/client related to logging.
 */
public final class GrpcLogUtil {

    /**
     * Returns a {@link RpcRequest} corresponding to the given {@link MethodDescriptor}.
     */
    public static RpcRequest rpcRequest(MethodDescriptor<?, ?> method, String simpleMethodName) {
        // See below to learn why we use GrpcLogUtil.class here.
        return RpcRequest.of(GrpcLogUtil.class, method.getServiceName(), simpleMethodName, ImmutableList.of());
    }

    /**
     * Returns a {@link RpcRequest} corresponding to the given {@link MethodDescriptor}.
     */
    public static RpcRequest rpcRequest(MethodDescriptor<?, ?> method, String simpleMethodName,
                                        Object message) {
        // We don't actually use the RpcRequest for request processing since it doesn't fit well with streaming.
        // We still populate it with a reasonable method name for use in logging. The service type is currently
        // arbitrarily set as gRPC doesn't use Class<?> to represent services. The method.getServiceName() is
        // actually used for logging.
        return RpcRequest.of(GrpcLogUtil.class, method.getServiceName(),
                             simpleMethodName, ImmutableList.of(message));
    }

    /**
     * Returns a {@link RpcResponse} corresponding to the given {@link StatusAndMetadata}.
     */
    public static RpcResponse rpcResponse(StatusAndMetadata statusAndMetadata, @Nullable Object message) {
        final Status status = statusAndMetadata.status();
        if (status.isOk()) {
            return RpcResponse.of(message);
        } else {
            return RpcResponse.ofFailure(statusAndMetadata.asRuntimeException());
        }
    }

    private GrpcLogUtil() {}
}
