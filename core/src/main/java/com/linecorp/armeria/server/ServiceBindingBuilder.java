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
import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A builder class for binding a {@link Service} fluently. This class can be instantiated through
 * {@link ServerBuilder#route()}. You can also configure a {@link Service} using
 * {@link ServerBuilder#withRoute(Consumer)}.
 *
 * <p>Call {@link #build(Service)} to build the {@link Service} and return to the {@link ServerBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 *
 * sb.route()                                      // Configure the first service.
 *   .post("/foo/bar")
 *   .consumes(JSON, PLAIN_TEXT_UTF_8)
 *   .produces(JSON_UTF_8)
 *   .requestTimeoutMillis(5000)
 *   .maxRequestLength(8192)
 *   .verboseResponses(true)
 *   .contentPreview(500)
 *   .build((ctx, req) -> HttpResponse.of(OK));    // Return to the ServerBuilder.
 *
 * // Configure the second service with Consumer.
 * sb.withRoute(builder -> builder.path("/baz")
 *                                .methods(HttpMethod.GET, HttpMethod.POST)
 *                                .build((ctx, req) -> HttpResponse.of(OK)));
 * }</pre>
 *
 * @see VirtualHostServiceBindingBuilder
 */
public final class ServiceBindingBuilder extends AbstractServiceBindingBuilder {

    private final ServerBuilder serverBuilder;

    ServiceBindingBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    @Override
    public ServiceBindingBuilder path(String pathPattern) {
        return (ServiceBindingBuilder) super.path(pathPattern);
    }

    /**
     * {@inheritDoc}
     * @deprecated Use {@link #pathPrefix(String)}.
     */
    @Override
    @Deprecated
    public ServiceBindingBuilder pathUnder(String prefix) {
        return (ServiceBindingBuilder) super.pathPrefix(prefix);
    }

    @Override
    public ServiceBindingBuilder pathPrefix(String prefix) {
        return (ServiceBindingBuilder) super.pathPrefix(prefix);
    }

    @Override
    public ServiceBindingBuilder methods(HttpMethod... methods) {
        return (ServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public ServiceBindingBuilder methods(Iterable<HttpMethod> methods) {
        return (ServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public ServiceBindingBuilder get(String pathPattern) {
        return (ServiceBindingBuilder) super.get(pathPattern);
    }

    @Override
    public ServiceBindingBuilder post(String pathPattern) {
        return (ServiceBindingBuilder) super.post(pathPattern);
    }

    @Override
    public ServiceBindingBuilder put(String pathPattern) {
        return (ServiceBindingBuilder) super.put(pathPattern);
    }

    @Override
    public ServiceBindingBuilder patch(String pathPattern) {
        return (ServiceBindingBuilder) super.patch(pathPattern);
    }

    @Override
    public ServiceBindingBuilder delete(String pathPattern) {
        return (ServiceBindingBuilder) super.delete(pathPattern);
    }

    @Override
    public ServiceBindingBuilder options(String pathPattern) {
        return (ServiceBindingBuilder) super.options(pathPattern);
    }

    @Override
    public ServiceBindingBuilder head(String pathPattern) {
        return (ServiceBindingBuilder) super.head(pathPattern);
    }

    @Override
    public ServiceBindingBuilder trace(String pathPattern) {
        return (ServiceBindingBuilder) super.trace(pathPattern);
    }

    @Override
    public ServiceBindingBuilder connect(String pathPattern) {
        return (ServiceBindingBuilder) super.connect(pathPattern);
    }

    @Override
    public ServiceBindingBuilder consumes(MediaType... consumeTypes) {
        return (ServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public ServiceBindingBuilder consumes(Iterable<MediaType> consumeTypes) {
        return (ServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public ServiceBindingBuilder produces(MediaType... produceTypes) {
        return (ServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public ServiceBindingBuilder produces(Iterable<MediaType> produceTypes) {
        return (ServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public ServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        return (ServiceBindingBuilder) super.requestTimeout(requestTimeout);
    }

    @Override
    public ServiceBindingBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        return (ServiceBindingBuilder) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public ServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        return (ServiceBindingBuilder) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public ServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        return (ServiceBindingBuilder) super.verboseResponses(verboseResponses);
    }

    @Override
    public ServiceBindingBuilder requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        return (ServiceBindingBuilder) super.requestContentPreviewerFactory(factory);
    }

    @Override
    public ServiceBindingBuilder responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        return (ServiceBindingBuilder) super.responseContentPreviewerFactory(factory);
    }

    @Override
    public ServiceBindingBuilder contentPreview(int length) {
        return (ServiceBindingBuilder) super.contentPreview(length);
    }

    @Override
    public ServiceBindingBuilder contentPreview(int length, Charset defaultCharset) {
        return (ServiceBindingBuilder) super.contentPreview(length, defaultCharset);
    }

    @Override
    public ServiceBindingBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        return (ServiceBindingBuilder) super.contentPreviewerFactory(factory);
    }

    @Override
    public ServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        return (ServiceBindingBuilder) super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    ServiceBindingBuilder decorator(Function<T, R> decorator) {
        return (ServiceBindingBuilder) super.decorator(decorator);
    }

    /**
     * Sets the {@link Service} and returns the {@link ServerBuilder} that this
     * {@link ServiceBindingBuilder} was created from.
     *
     * @throws IllegalStateException if the path that the {@link Service} will be bound to is not specified
     */
    public ServerBuilder build(Service<HttpRequest, HttpResponse> service) {
        build0(service);
        return serverBuilder;
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        serverBuilder.serviceConfigBuilder(serviceConfigBuilder);
    }
}
