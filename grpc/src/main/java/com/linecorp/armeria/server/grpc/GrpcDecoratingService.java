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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

import io.grpc.ServerServiceDefinition;

/**
 * A Wrapper class for {@link GrpcService} to route decorated function according to a request path.
 */
final class GrpcDecoratingService extends SimpleDecoratingHttpService implements GrpcService {

    private final GrpcService delegate;

    /**
     * A pair of a method path (e.g. '/armeria.grpc.sample.SampleService/UnaryCall') and decorators
     * that are extracted from `@Decorator` and composite already.
     */
    private final Map<String, HttpService> methodDecorators;

    GrpcDecoratingService(GrpcService delegate,
                          Map<String, HttpService> methodDecorators) {
        super(delegate);
        this.delegate = delegate;
        this.methodDecorators = methodDecorators;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpService methodDecorator = methodDecorators.get(req.path());
        if (methodDecorator != null) {
            return methodDecorator.serve(ctx, req);
        }
        return unwrap().serve(ctx, req);
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
    public Set<SerializationFormat> supportedSerializationFormats() {
        return delegate.supportedSerializationFormats();
    }
}
