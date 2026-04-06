/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSupport;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Converts HTTP/JSON requests to gRPC and delegates them to the given {@link HttpService}.
 *
 * <p>This service is useful when you want to place HTTP/JSON transcoding in front of another
 * service, such as a proxy that forwards requests to an upstream gRPC server or a {@link GrpcService}
 * bound at a different path.
 *
 * <p>Requests that do not match any configured transcoding route are handled by the fallback
 * {@link HttpService}, which defaults to returning {@link HttpStatus#NOT_FOUND}.
 * Use {@link DelegatingHttpJsonTranscodingServiceBuilder#fallback(HttpService)} to customize
 * this behavior.
 */
@UnstableApi
public final class DelegatingHttpJsonTranscodingService implements HttpServiceWithRoutes {

    private final HttpService delegate;
    private final HttpJsonTranscoder transcoder;
    private final HttpService fallback;

    /**
     * Returns a new {@link DelegatingHttpJsonTranscodingServiceBuilder}.
     */
    public static DelegatingHttpJsonTranscodingServiceBuilder builder(HttpService delegate) {
        return new DelegatingHttpJsonTranscodingServiceBuilder(delegate);
    }

    DelegatingHttpJsonTranscodingService(HttpService delegate, HttpJsonTranscoder transcoder,
                                         HttpService fallback) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.transcoder = requireNonNull(transcoder, "transcoder");
        this.fallback = requireNonNull(fallback, "fallback");
    }

    @Override
    public Set<Route> routes() {
        return transcoder.routes();
    }

    @Nullable
    @Override
    public <T> T as(Class<T> type) {
        requireNonNull(type, "type");
        // Expose the internal HttpEndpointSupport without leaking it into the public API.
        if (type == HttpEndpointSupport.class) {
            return type.cast(transcoder);
        }
        return HttpServiceWithRoutes.super.as(type);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (ctx.attr(HttpJsonTranscoder.HTTP_JSON_GRPC_METHOD_INFO) != null) {
            throw new IllegalStateException(
                    "DelegatingHttpJsonTranscodingService must not be used as a delegate of " +
                    "another DelegatingHttpJsonTranscodingService.");
        }
        final HttpJsonTranscoder.TranscodingSpec spec =
                transcoder.findSpec(ctx.config().mappedRoute());
        if (spec != null) {
            return transcoder.serve(ctx, req, spec, delegate);
        }
        return fallback.serve(ctx, req);
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        return ExchangeType.UNARY;
    }
}
