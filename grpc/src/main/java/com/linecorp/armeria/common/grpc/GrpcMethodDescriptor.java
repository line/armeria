/*
 * Copyright 2025 LINE Corporation
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

import io.grpc.MethodDescriptor;
import io.netty.util.AttributeKey;

/**
 * Retrieves {@link io.grpc.MethodDescriptor} from a given {@link RequestContext}. This might be useful while
 * providing common middleware that needs to act based on the method invoked based on protobuf custom options.
 * <p>
 * Here is an example of how custom call options might be propagated between a gRPC stub and a decorator.
 * </p>
 * <pre>{@code
 *     MyGrpcStub client = GrpcClients
 *         .builder(grpcServerUri)
 *         .decorator((delegate, ctx, req) -> {
 *             MethodDescriptor descriptor = GrpcMethodDescriptor.get(ctx);
 *             boolean retryable = descriptor.isIdempotent() || descriptor.isSafe()
 *
 *             // act on retryable if needed
 *
 *             return delegate.execute(ctx, req);
 *         })
 *         .build(MyGrpcStub.class)
 * }</pre>
 */
@UnstableApi
public final class GrpcMethodDescriptor {

    private static final AttributeKey<MethodDescriptor<?, ?>> GRPC_METHOD_DESCRIPTOR = AttributeKey.valueOf(
            GrpcMethodDescriptor.class, "GRPC_METHOD_DESCRIPTOR");

    /**
     * Returns {@link MethodDescriptor} which was set to the specified {@link RequestContext} using
     * {@link #set(RequestContext, MethodDescriptor)}.
     */
    @Nullable
    public static MethodDescriptor<?, ?> get(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return ctx.attr(GRPC_METHOD_DESCRIPTOR);
    }

    /**
     * Sets the specified {@link MethodDescriptor} to the {@link RequestContext}.
     */
    public static void set(RequestContext ctx, MethodDescriptor<?, ?> descriptor) {
        requireNonNull(ctx, "ctx");
        requireNonNull(descriptor, "descriptor");
        ctx.setAttr(GRPC_METHOD_DESCRIPTOR, descriptor);
    }

    private GrpcMethodDescriptor() {
    }
}
