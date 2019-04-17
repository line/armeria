/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * A builder class for building a {@link Service} fluently. This class can only be created through
 * {@link VirtualHostBuilder#route()}.
 *
 * <p>Call {@link #service(Service)} to build the {@link Service} and return to the {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = new ServerBuilder();
 * sb.withVirtualHost("example.com")
 *   .route().get("/foo/bar")                      // Configure the first service.
 *   .consumes(JSON, PLAIN_TEXT_UTF_8)
 *   .produces(JSON_UTF_8, PLAIN_TEXT_UTF_8)
 *   .requestTimeoutMillis(10)
 *   .maxRequestLength(8192)
 *   .verboseResponses(true)
 *   .contentPreview(500)
 *   .service((ctx, req) -> HttpResponse.of(OK))   // Return to the VirtualHostBuilder.
 *   .accessLogger(myLogger)                       // VirtualHostBuilder
 *   .route().path("/baz")                         // Configure the second service.
 *   .methods(HttpMethod.GET, HttpMethod.POST)
 *   .service((ctx, req) -> HttpResponse.of(OK));  // Return to the VirtualHostBuilder.
 * }</pre>
 *
 * @see RouteBuilder
 */
public final class VirtualHostRouteBuilder
        extends AbstractRouteBuilder<VirtualHostRouteBuilder, VirtualHostBuilder> {

    private final VirtualHostBuilder virtualHostBuilder;

    VirtualHostRouteBuilder(VirtualHostBuilder virtualHostBuilder) {
        this.virtualHostBuilder = requireNonNull(virtualHostBuilder, "virtualHostBuilder");
    }

    /**
     * Sets the {@link Service} and returns the {@link VirtualHostBuilder} that you create this
     * {@link VirtualHostRouteBuilder} from.
     *
     * @throws IllegalStateException if the path that the {@link Service} will be bound to is not specified
     */
    @Override
    public VirtualHostBuilder service(Service<HttpRequest, HttpResponse> service) {
        build(service);
        return virtualHostBuilder;
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        virtualHostBuilder.serviceConfigBuilder(serviceConfigBuilder);
    }
}
