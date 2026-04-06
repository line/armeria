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

package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSpecification;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSupport;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.grpc.HttpJsonTranscoder.TranscodingSpec;
import com.linecorp.armeria.server.grpc.HttpJsonTranscoderBuilder.HttpJsonGrpcMethod;

import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

/**
 * Converts HTTP/JSON request to gRPC request and delegates it to the {@link FramedGrpcService}.
 */
final class HttpJsonTranscodingGrpcService extends SimpleDecoratingHttpService
        implements GrpcService, HttpEndpointSupport {
    private final GrpcService delegate;
    private final HttpJsonTranscoder transcoder;
    private final Set<Route> routes;
    private final DelegatingHttpJsonTranscodingService transcodingService;

    HttpJsonTranscodingGrpcService(GrpcService delegate, HttpJsonTranscoder transcoder) {
        super(delegate);
        this.delegate = delegate;
        this.transcoder = requireNonNull(transcoder, "engine");
        routes = buildRoutes(delegate.routes(), this.transcoder.routes());
        transcodingService = new DelegatingHttpJsonTranscodingService(delegate, transcoder, delegate);
    }

    @Nullable
    @Override
    public HttpEndpointSpecification httpEndpointSpecification(Route route) {
        return transcoder.httpEndpointSpecification(route);
    }

    /**
     * Returns the {@link Route}s which are supported by this service and the {@code delegate}.
     */
    @Override
    public Set<Route> routes() {
        return routes;
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        return UnframedGrpcSupport.exchangeType(routingContext, delegate);
    }

    @Override
    public boolean isFramed() {
        return false;
    }

    @Override
    public List<ServerServiceDefinition> services() {
        return delegate.services();
    }

    @Override
    public Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute() {
        return delegate.methodsByRoute();
    }

    @Override
    public Set<SerializationFormat> supportedSerializationFormats() {
        return delegate.supportedSerializationFormats();
    }

    @Nullable
    @Override
    public ServerMethodDefinition<?, ?> methodDefinition(ServiceRequestContext ctx) {
        final TranscodingSpec spec = transcoder.findSpec(ctx.config().mappedRoute());
        if (spec != null) {
            final HttpJsonGrpcMethod method = spec.method();
            if (method.definition != null) {
                return method.definition;
            }
        }
        return delegate.methodDefinition(ctx);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (ctx.attr(HttpJsonTranscoder.HTTP_JSON_GRPC_METHOD_INFO) != null) {
            // GrpcService with transcoding enabled is nested inside a DelegatingHttpJsonTranscodingService.
            return delegate.serve(ctx, req);
        }
        return transcodingService.serve(ctx, req);
    }

    private static Set<Route> buildRoutes(Set<Route> delegateRoutes, Set<Route> transcodingRoutes) {
        final LinkedHashSet<Route> linkedHashSet = new LinkedHashSet<>(delegateRoutes.size() +
                                                                       transcodingRoutes.size());
        linkedHashSet.addAll(delegateRoutes);
        linkedHashSet.addAll(transcodingRoutes);
        return Collections.unmodifiableSet(linkedHashSet);
    }
}
