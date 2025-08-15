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

package com.linecorp.armeria.client.grpc;

import static com.linecorp.armeria.internal.client.grpc.InternalGrpcClientCall.GRPC_CALL_OPTIONS;
import static com.linecorp.armeria.internal.client.grpc.InternalGrpcClientCall.GRPC_METHOD_DESCRIPTOR;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.CallOptions;
import io.grpc.MethodDescriptor;

/**
 * Retrieves gRPC context from a given {@link RequestContext}. This might be useful while providing
 * common middleware that needs to act on options configured at the gRPC client's call site.
 * <p>
 * Here is an example of how custom call options might be propagated between a gRPC stub and a decorator.
 * </p>
 * <pre>{@code
 *     MyGrpcStub client = GrpcClients
 *         .builder(grpcServerUri)
 *         .decorator((delegate, ctx, req) -> {
 *             CallOptions options = GrpcClientCall.callOptions(ctx);
 *             MyOption myOption = options.getOption(myOptionKey);
 *             MethodDescriptor descriptor = GrpcClientCall.methodDescriptor(ctx);
 *             boolean retryable = descriptor.isIdempotent() || descriptor.isSafe()
 *
 *             // act on myOption or retryable if needed
 *
 *             return delegate.execute(ctx, req);
 *         })
 *         .build(MyGrpcStub.class)
 *
 *     client
 *       .withOption(myOptionKey, myOptionValue)
 *       .echo(...)
 * }</pre>
 */
@UnstableApi
public final class GrpcClientCall {

    /**
     * Returns {@link MethodDescriptor} for the method called.
     */
    @Nullable
    public static MethodDescriptor<?, ?> methodDescriptor(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return ctx.attr(GRPC_METHOD_DESCRIPTOR);
    }

    /**
     * Returns {@link CallOptions} which were used in the call.
     */
    @Nullable
    public static CallOptions callOptions(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return ctx.attr(GRPC_CALL_OPTIONS);
    }

    private GrpcClientCall() {
    }
}
