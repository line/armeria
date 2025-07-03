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

package com.linecorp.armeria.internal.client.grpc;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RequestContext;

import io.grpc.CallOptions;
import io.grpc.MethodDescriptor;
import io.netty.util.AttributeKey;

public final class InternalGrpcClientCall {

    public static final AttributeKey<MethodDescriptor<?, ?>> GRPC_METHOD_DESCRIPTOR = AttributeKey.valueOf(
            InternalGrpcClientCall.class, "GRPC_METHOD_DESCRIPTOR");

    public static final AttributeKey<CallOptions> GRPC_CALL_OPTIONS = AttributeKey.valueOf(
            InternalGrpcClientCall.class, "GRPC_CALL_OPTIONS");

    /**
     * Sets the specified {@link CallOptions} and {@link MethodDescriptor} to the {@link RequestContext}.
     */
    static void set(RequestContext ctx, CallOptions options, MethodDescriptor<?, ?> descriptor) {
        requireNonNull(ctx, "ctx");
        requireNonNull(options, "options");
        requireNonNull(descriptor, "descriptor");
        ctx.setAttr(GRPC_CALL_OPTIONS, options);
        ctx.setAttr(GRPC_METHOD_DESCRIPTOR, descriptor);
    }

    private InternalGrpcClientCall() {
    }
}
