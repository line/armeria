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

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;

/**
 * A builder class for binding a {@link Service} fluently. This class can only be instantiated through
 * {@link VirtualHostBuilder#route()}.
 *
 * <p>Call {@link #service(Service)} to build the {@link Service} and return to the {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = new ServerBuilder();
 * sb.withVirtualHost("example.com")
 *   .route().get("/foo/bar")                              // Configure the first service in "example.com".
 *           .consumes(JSON, PLAIN_TEXT_UTF_8)
 *           .produces(JSON_UTF_8)
 *           .requestTimeoutMillis(5000)
 *           .maxRequestLength(8192)
 *           .verboseResponses(true)
 *           .contentPreview(500)
 *           .service((ctx, req) -> HttpResponse.of(OK))   // Return to the VirtualHostBuilder.
 *           .and()                                        // Return to the ServerBuilder.
 * sb.withVirtualHost("example2.com")
 *   .route().path("/baz")                                 // Configure the second service "example2.com".
 *           .methods(HttpMethod.GET, HttpMethod.POST)
 *           .service((ctx, req) -> HttpResponse.of(OK));  // Return to the VirtualHostBuilder.
 * }</pre>
 *
 * @see RouteBuilder
 */
public final class VirtualHostRouteBuilder extends AbstractRouteBuilder {

    private final VirtualHostBuilder virtualHostBuilder;

    VirtualHostRouteBuilder(VirtualHostBuilder virtualHostBuilder) {
        this.virtualHostBuilder = requireNonNull(virtualHostBuilder, "virtualHostBuilder");
    }

    @Override
    public VirtualHostRouteBuilder path(String pathPattern) {
        return (VirtualHostRouteBuilder) super.path(pathPattern);
    }

    @Override
    public VirtualHostRouteBuilder pathUnder(String prefix) {
        return (VirtualHostRouteBuilder) super.pathUnder(prefix);
    }

    @Override
    public VirtualHostRouteBuilder pathMapping(PathMapping pathMapping) {
        return (VirtualHostRouteBuilder) super.pathMapping(pathMapping);
    }

    @Override
    public VirtualHostRouteBuilder methods(HttpMethod... methods) {
        return (VirtualHostRouteBuilder) super.methods(methods);
    }

    @Override
    public VirtualHostRouteBuilder methods(Iterable<HttpMethod> methods) {
        return (VirtualHostRouteBuilder) super.methods(methods);
    }

    @Override
    public VirtualHostRouteBuilder get(String pathPattern) {
        return (VirtualHostRouteBuilder) super.get(pathPattern);
    }

    @Override
    public VirtualHostRouteBuilder post(String pathPattern) {
        return (VirtualHostRouteBuilder) super.post(pathPattern);
    }

    @Override
    public VirtualHostRouteBuilder put(String pathPattern) {
        return (VirtualHostRouteBuilder) super.put(pathPattern);
    }

    @Override
    public VirtualHostRouteBuilder patch(String pathPattern) {
        return (VirtualHostRouteBuilder) super.patch(pathPattern);
    }

    @Override
    public VirtualHostRouteBuilder delete(String pathPattern) {
        return (VirtualHostRouteBuilder) super.delete(pathPattern);
    }

    @Override
    public VirtualHostRouteBuilder options(String pathPattern) {
        return (VirtualHostRouteBuilder) super.options(pathPattern);
    }

    @Override
    public VirtualHostRouteBuilder head(String pathPattern) {
        return (VirtualHostRouteBuilder) super.head(pathPattern);
    }

    @Override
    public VirtualHostRouteBuilder trace(String pathPattern) {
        return (VirtualHostRouteBuilder) super.trace(pathPattern);
    }

    @Override
    public VirtualHostRouteBuilder connect(String pathPattern) {
        return (VirtualHostRouteBuilder) super.connect(pathPattern);
    }

    @Override
    public VirtualHostRouteBuilder consumes(MediaType... consumeTypes) {
        return (VirtualHostRouteBuilder) super.consumes(consumeTypes);
    }

    @Override
    public VirtualHostRouteBuilder consumes(Iterable<MediaType> consumeTypes) {
        return (VirtualHostRouteBuilder) super.consumes(consumeTypes);
    }

    @Override
    public VirtualHostRouteBuilder produces(MediaType... produceTypes) {
        return (VirtualHostRouteBuilder) super.produces(produceTypes);
    }

    @Override
    public VirtualHostRouteBuilder produces(Iterable<MediaType> produceTypes) {
        return (VirtualHostRouteBuilder) super.produces(produceTypes);
    }

    @Override
    public VirtualHostRouteBuilder requestTimeout(Duration requestTimeout) {
        return (VirtualHostRouteBuilder) super.requestTimeout(requestTimeout);
    }

    @Override
    public VirtualHostRouteBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        return (VirtualHostRouteBuilder) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public VirtualHostRouteBuilder maxRequestLength(long maxRequestLength) {
        return (VirtualHostRouteBuilder) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public VirtualHostRouteBuilder verboseResponses(boolean verboseResponses) {
        return (VirtualHostRouteBuilder) super.verboseResponses(verboseResponses);
    }

    @Override
    public VirtualHostRouteBuilder requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        return (VirtualHostRouteBuilder) super.requestContentPreviewerFactory(factory);
    }

    @Override
    public VirtualHostRouteBuilder responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        return (VirtualHostRouteBuilder) super.responseContentPreviewerFactory(factory);
    }

    @Override
    public VirtualHostRouteBuilder contentPreview(int length) {
        return (VirtualHostRouteBuilder) super.contentPreview(length);
    }

    @Override
    public VirtualHostRouteBuilder contentPreview(int length, Charset defaultCharset) {
        return (VirtualHostRouteBuilder) super.contentPreview(length, defaultCharset);
    }

    @Override
    public VirtualHostRouteBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        return (VirtualHostRouteBuilder) super.contentPreviewerFactory(factory);
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    VirtualHostRouteBuilder decorator(Function<T, R> decorator) {
        return (VirtualHostRouteBuilder) super.decorator(decorator);
    }

    /**
     * Sets the {@link Service} and returns the {@link VirtualHostBuilder} that this
     * {@link VirtualHostRouteBuilder} was created from.
     *
     * @throws IllegalStateException if the path that the {@link Service} will be bound to is not specified
     */
    public VirtualHostBuilder service(Service<HttpRequest, HttpResponse> service) {
        build(service);
        return virtualHostBuilder;
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        virtualHostBuilder.serviceConfigBuilder(serviceConfigBuilder);
    }
}
