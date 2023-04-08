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

package com.linecorp.armeria.common.grpc.protocol;

import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.InternalGrpcWebTrailers;

/**
 * Retrieves <a href="https://grpc.io/docs/languages/web/basics/">gRPC-Web</a> trailers.
 */
@UnstableApi
public final class GrpcWebTrailers {

    /**
     * Returns the gRPC-Web trailers which was set to the specified {@link RequestContext} using
     * {@link #set(RequestContext, HttpHeaders)}.
     *
     * <p>A gRPC-Web response does not contain a separated trailers according to the
     * <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md#protocol-differences-vs-grpc-over-http2">
     * gRPC-Web spec</a>:
     * <ul>
     *   <li>Trailers must be the last message of the response.</li>
     *   <li>Trailers may be sent together with response headers, with no message in the body.</li>
     * </ul>
     * That means the response trailers should be pulled out from {@link AggregatedHttpResponse#headers()}}
     * or parsed from {@link AggregatedHttpResponse#content()}.
     *
     * <p>This method is useful when {@link RetryRuleWithContent} needs {@link GrpcHeaderNames#GRPC_STATUS}
     * to decide whether to retry. For example:
     * <pre>{@code
     * Clients.builder(grpcServerUri)
     *        .decorator(RetryingClient.newDecorator(
     *                RetryRuleWithContent.onResponse((ctx, response) -> {
     *                    // Note that we should aggregate the response to get the trailers.
     *                    return response.aggregate().thenApply(aggregated -> {
     *                        HttpHeaders trailers = GrpcWebTrailers.get(ctx);
     *                        // Retry if the 'grpc-status' is not equal to 0.
     *                        return trailers != null && trailers.getInt(GrpcHeaderNames.GRPC_STATUS) != 0;
     *                    });
     *                })))
     *        .build(MyGrpcStub.class);
     * }</pre>
     */
    @Nullable
    public static HttpHeaders get(RequestContext ctx) {
        return InternalGrpcWebTrailers.get(ctx);
    }

    /**
     * Sets the specified gRPC-Web trailers to the {@link RequestContext}.
     */
    public static void set(RequestContext ctx, HttpHeaders trailers) {
        InternalGrpcWebTrailers.set(ctx, trailers);
    }

    private GrpcWebTrailers() {}
}
