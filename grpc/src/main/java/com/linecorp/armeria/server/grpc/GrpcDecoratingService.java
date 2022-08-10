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

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

/**
 * A Wrapper class for {@link GrpcService} to route decorated function according to a request path.
 */
final class GrpcDecoratingService extends SimpleDecoratingHttpService implements GrpcService {

    private final GrpcService delegate;

    private final HandlerRegistry handlerRegistry;

    @Nullable
    private Map<ServerMethodDefinition<?, ?>, HttpService> decorated;

    GrpcDecoratingService(GrpcService delegate, HandlerRegistry handlerRegistry) {
        super(delegate);
        this.delegate = delegate;
        this.handlerRegistry = handlerRegistry;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        super.serviceAdded(cfg);
        final DependencyInjector dependencyInjector = cfg.server()
                                                         .config()
                                                         .dependencyInjector();
        decorated = handlerRegistry.applyDecorators(delegate, dependencyInjector);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        assert decorated != null;
        final ServerMethodDefinition<?, ?> methodDefinition = methodDefinition(ctx);
        if (methodDefinition == null) {
            return delegate.serve(ctx, req);
        }
        final HttpService decoratedService = decorated.get(methodDefinition);
        if (decoratedService != null) {
            return decoratedService.serve(ctx, req);
        }
        return delegate.serve(ctx, req);
    }

    @VisibleForTesting
    HandlerRegistry handlerRegistry() {
        return handlerRegistry;
    }

    @Override
    public Set<Route> routes() {
        return delegate.routes();
    }

    @Override
    public boolean isFramed() {
        return delegate.isFramed();
    }

    @Override
    public List<ServerServiceDefinition> services() {
        return delegate.services();
    }

    @Override
    public ServerMethodDefinition<?, ?> methodDefinition(ServiceRequestContext ctx) {
        return delegate.methodDefinition(ctx);
    }

    @Override
    public Map<String, ServerMethodDefinition<?, ?>> methods() {
        return delegate.methods();
    }

    @Override
    public Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute() {
        return delegate.methodsByRoute();
    }

    @Override
    public Set<SerializationFormat> supportedSerializationFormats() {
        return delegate.supportedSerializationFormats();
    }
}
