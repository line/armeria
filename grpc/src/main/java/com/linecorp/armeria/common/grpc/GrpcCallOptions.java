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

import com.linecorp.armeria.client.grpc.GrpcClientCall;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.CallOptions;
import io.netty.util.AttributeKey;

/**
 * Retrieves {@link CallOptions} from a given {@link RequestContext}. This might be useful while providing
 * common middleware that needs to act on options configured at the gRPC client's call site.
 * <p>
 * Here is an example of how custom call options might be propagated between a gRPC stub and a decorator.
 * </p>
 * <pre>{@code
 *     MyGrpcStub client = GrpcClients
 *         .builder(grpcServerUri)
 *         .decorator((delegate, ctx, req) -> {
 *             CallOptions options = GrpcCallOptions.get(ctx);
 *             MyOption myOption = options.getOption(myOptionKey);
 *
 *             // act on myOption if needed
 *
 *             return delegate.execute(ctx, req);
 *         })
 *         .build(MyGrpcStub.class)
 *
 *     client
 *       .withOption(myOptionKey, myOptionValue)
 *       .echo(...)
 * }</pre>
 *
 * @deprecated Use {@link GrpcClientCall} instead.
 */
@UnstableApi
@Deprecated
public final class GrpcCallOptions {

    private static final AttributeKey<CallOptions> GRPC_CALL_OPTIONS = AttributeKey.valueOf(
            GrpcCallOptions.class, "GRPC_CALL_OPTIONS");

    /**
     * Returns {@link CallOptions} which were passed during the call.
     */
    @Nullable
    public static CallOptions get(RequestContext ctx) {
        return GrpcClientCall.callOptions(ctx);
    }

    private GrpcCallOptions() {}
}
