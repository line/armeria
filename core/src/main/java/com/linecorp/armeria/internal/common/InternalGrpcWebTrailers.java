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

package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

/**
 * Retrieves <a href="https://grpc.io/docs/languages/web/basics/">gRPC-Web</a> trailers.
 */
public final class InternalGrpcWebTrailers {

    private static final AttributeKey<HttpHeaders> GRPC_WEB_TRAILERS = AttributeKey.valueOf(
            InternalGrpcWebTrailers.class, "GRPC_WEB_TRAILERS");

    /**
     * Returns the gRPC-Web trailers which was set to the specified {@link RequestContext} using
     * {@link #set(RequestContext, HttpHeaders)}.
     */
    @Nullable
    public static HttpHeaders get(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return ctx.attr(GRPC_WEB_TRAILERS);
    }

    /**
     * Sets the specified gRPC-Web trailers to the {@link RequestContext}.
     */
    public static void set(RequestContext ctx, HttpHeaders trailers) {
        requireNonNull(ctx, "ctx");
        requireNonNull(trailers, "trailers");
        ctx.setAttr(GRPC_WEB_TRAILERS, trailers);
    }

    private InternalGrpcWebTrailers() {}
}
