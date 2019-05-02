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
 * {@link ServerBuilder#route()}.
 *
 * <p>Call {@link #service(Service)} to build the {@link Service} and return to the {@link ServerBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = new ServerBuilder();
 * sb.route().get("/foo/bar")                              // Configure the first service.
 *           .consumes(JSON, PLAIN_TEXT_UTF_8)
 *           .produces(JSON_UTF_8)
 *           .requestTimeoutMillis(5000)
 *           .maxRequestLength(8192)
 *           .verboseResponses(true)
 *           .contentPreview(500)
 *           .service((ctx, req) -> HttpResponse.of(OK))   // Return to the ServerBuilder.
 *   .route().path("/baz")                                 // Configure the second service.
 *           .methods(HttpMethod.GET, HttpMethod.POST)
 *           .service((ctx, req) -> HttpResponse.of(OK));  // Return to the ServerBuilder.
 * }</pre>
 *
 *
 *
 * @see VirtualHostRouteBuilder
 */
public final class RouteBuilder extends AbstractRouteBuilder {

    private final ServerBuilder serverBuilder;

    RouteBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    @Override
    public RouteBuilder path(String pathPattern) {
        return (RouteBuilder) super.path(pathPattern);
    }

    @Override
    public RouteBuilder pathUnder(String prefix) {
        return (RouteBuilder) super.pathUnder(prefix);
    }

    @Override
    public RouteBuilder pathMapping(PathMapping pathMapping) {
        return (RouteBuilder) super.pathMapping(pathMapping);
    }

    @Override
    public RouteBuilder methods(HttpMethod... methods) {
        return (RouteBuilder) super.methods(methods);
    }

    @Override
    public RouteBuilder methods(Iterable<HttpMethod> methods) {
        return (RouteBuilder) super.methods(methods);
    }

    @Override
    public RouteBuilder get(String pathPattern) {
        return (RouteBuilder) super.get(pathPattern);
    }

    @Override
    public RouteBuilder post(String pathPattern) {
        return (RouteBuilder) super.post(pathPattern);
    }

    @Override
    public RouteBuilder put(String pathPattern) {
        return (RouteBuilder) super.put(pathPattern);
    }

    @Override
    public RouteBuilder patch(String pathPattern) {
        return (RouteBuilder) super.patch(pathPattern);
    }

    @Override
    public RouteBuilder delete(String pathPattern) {
        return (RouteBuilder) super.delete(pathPattern);
    }

    @Override
    public RouteBuilder options(String pathPattern) {
        return (RouteBuilder) super.options(pathPattern);
    }

    @Override
    public RouteBuilder head(String pathPattern) {
        return (RouteBuilder) super.head(pathPattern);
    }

    @Override
    public RouteBuilder trace(String pathPattern) {
        return (RouteBuilder) super.trace(pathPattern);
    }

    @Override
    public RouteBuilder connect(String pathPattern) {
        return (RouteBuilder) super.connect(pathPattern);
    }

    @Override
    public RouteBuilder consumes(MediaType... consumeTypes) {
        return (RouteBuilder) super.consumes(consumeTypes);
    }

    @Override
    public RouteBuilder consumes(Iterable<MediaType> consumeTypes) {
        return (RouteBuilder) super.consumes(consumeTypes);
    }

    @Override
    public RouteBuilder produces(MediaType... produceTypes) {
        return (RouteBuilder) super.produces(produceTypes);
    }

    @Override
    public RouteBuilder produces(Iterable<MediaType> produceTypes) {
        return (RouteBuilder) super.produces(produceTypes);
    }

    @Override
    public RouteBuilder requestTimeout(Duration requestTimeout) {
        return (RouteBuilder) super.requestTimeout(requestTimeout);
    }

    @Override
    public RouteBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        return (RouteBuilder) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public RouteBuilder maxRequestLength(long maxRequestLength) {
        return (RouteBuilder) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public RouteBuilder verboseResponses(boolean verboseResponses) {
        return (RouteBuilder) super.verboseResponses(verboseResponses);
    }

    @Override
    public RouteBuilder requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        return (RouteBuilder) super.requestContentPreviewerFactory(factory);
    }

    @Override
    public RouteBuilder responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        return (RouteBuilder) super.responseContentPreviewerFactory(factory);
    }

    @Override
    public RouteBuilder contentPreview(int length) {
        return (RouteBuilder) super.contentPreview(length);
    }

    @Override
    public RouteBuilder contentPreview(int length, Charset defaultCharset) {
        return (RouteBuilder) super.contentPreview(length, defaultCharset);
    }

    @Override
    public RouteBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        return (RouteBuilder) super.contentPreviewerFactory(factory);
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    RouteBuilder decorator(Function<T, R> decorator) {
        return (RouteBuilder) super.decorator(decorator);
    }

    /**
     * Sets the {@link Service} and returns the {@link ServerBuilder} that this {@link RouteBuilder} was
     * created from.
     *
     * @throws IllegalStateException if the path that the {@link Service} will be bound to is not specified
     */
    public ServerBuilder service(Service<HttpRequest, HttpResponse> service) {
        build(service);
        return serverBuilder;
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        serverBuilder.serviceConfigBuilder(serviceConfigBuilder);
    }
}
