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
 * {@link VirtualHostBuilder#route()}. You can also configure a {@link Service} using
 * {@link VirtualHostBuilder#withRoute(Consumer)}.
 *
 * <p>Call {@link #build(Service)} to build the {@link Service} and return to the {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = new ServerBuilder();
 * sb.virtualHost("example.com")
 *   .route()                                      // Configure the first service in "example.com".
 *   .post("/foo/bar")
 *   .consumes(JSON, PLAIN_TEXT_UTF_8)
 *   .produces(JSON_UTF_8)
 *   .requestTimeoutMillis(5000)
 *   .maxRequestLength(8192)
 *   .verboseResponses(true)
 *   .contentPreview(500)
 *   .build((ctx, req) -> HttpResponse.of(OK));    // Return to the VirtualHostBuilder.
 *
 * sb.virtualHost("example2.com")                  // Configure the second service "example2.com".
 *   .withRoute(builder -> builder.path("/baz")
 *                                .methods(HttpMethod.GET, HttpMethod.POST)
 *                                .build((ctx, req) -> HttpResponse.of(OK)));
 * }</pre>
 *
 * @see ServiceBindingBuilder
 */
public final class VirtualHostServiceBindingBuilder extends AbstractServiceBindingBuilder {

    private final VirtualHostBuilder virtualHostBuilder;

    VirtualHostServiceBindingBuilder(VirtualHostBuilder virtualHostBuilder) {
        this.virtualHostBuilder = requireNonNull(virtualHostBuilder, "virtualHostBuilder");
    }

    @Override
    public VirtualHostServiceBindingBuilder path(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.path(pathPattern);
    }

    /**
     * {@inheritDoc}
     * @deprecated Use {@link #pathPrefix(String)}.
     */
    @Override
    @Deprecated
    public VirtualHostServiceBindingBuilder pathUnder(String prefix) {
        return (VirtualHostServiceBindingBuilder) super.pathPrefix(prefix);
    }

    @Override
    public VirtualHostServiceBindingBuilder pathPrefix(String prefix) {
        return (VirtualHostServiceBindingBuilder) super.pathPrefix(prefix);
    }

    @Override
    public VirtualHostServiceBindingBuilder methods(HttpMethod... methods) {
        return (VirtualHostServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public VirtualHostServiceBindingBuilder methods(Iterable<HttpMethod> methods) {
        return (VirtualHostServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public VirtualHostServiceBindingBuilder get(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.get(pathPattern);
    }

    @Override
    public VirtualHostServiceBindingBuilder post(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.post(pathPattern);
    }

    @Override
    public VirtualHostServiceBindingBuilder put(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.put(pathPattern);
    }

    @Override
    public VirtualHostServiceBindingBuilder patch(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.patch(pathPattern);
    }

    @Override
    public VirtualHostServiceBindingBuilder delete(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.delete(pathPattern);
    }

    @Override
    public VirtualHostServiceBindingBuilder options(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.options(pathPattern);
    }

    @Override
    public VirtualHostServiceBindingBuilder head(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.head(pathPattern);
    }

    @Override
    public VirtualHostServiceBindingBuilder trace(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.trace(pathPattern);
    }

    @Override
    public VirtualHostServiceBindingBuilder connect(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.connect(pathPattern);
    }

    @Override
    public VirtualHostServiceBindingBuilder consumes(MediaType... consumeTypes) {
        return (VirtualHostServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public VirtualHostServiceBindingBuilder consumes(Iterable<MediaType> consumeTypes) {
        return (VirtualHostServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public VirtualHostServiceBindingBuilder produces(MediaType... produceTypes) {
        return (VirtualHostServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public VirtualHostServiceBindingBuilder produces(Iterable<MediaType> produceTypes) {
        return (VirtualHostServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public VirtualHostServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        return (VirtualHostServiceBindingBuilder) super.requestTimeout(requestTimeout);
    }

    @Override
    public VirtualHostServiceBindingBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        return (VirtualHostServiceBindingBuilder) super.requestTimeoutMillis(requestTimeoutMillis);
    }

    @Override
    public VirtualHostServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        return (VirtualHostServiceBindingBuilder) super.maxRequestLength(maxRequestLength);
    }

    @Override
    public VirtualHostServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        return (VirtualHostServiceBindingBuilder) super.verboseResponses(verboseResponses);
    }

    @Override
    public VirtualHostServiceBindingBuilder requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        return (VirtualHostServiceBindingBuilder) super.requestContentPreviewerFactory(factory);
    }

    @Override
    public VirtualHostServiceBindingBuilder responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        return (VirtualHostServiceBindingBuilder) super.responseContentPreviewerFactory(factory);
    }

    @Override
    public VirtualHostServiceBindingBuilder contentPreview(int length) {
        return (VirtualHostServiceBindingBuilder) super.contentPreview(length);
    }

    @Override
    public VirtualHostServiceBindingBuilder contentPreview(int length, Charset defaultCharset) {
        return (VirtualHostServiceBindingBuilder) super.contentPreview(length, defaultCharset);
    }

    @Override
    public VirtualHostServiceBindingBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        return (VirtualHostServiceBindingBuilder) super.contentPreviewerFactory(factory);
    }

    @Override
    public VirtualHostServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                            boolean shutdownOnStop) {
        return (VirtualHostServiceBindingBuilder) super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    VirtualHostServiceBindingBuilder decorator(Function<T, R> decorator) {
        return (VirtualHostServiceBindingBuilder) super.decorator(decorator);
    }

    /**
     * Sets the {@link Service} and returns the {@link VirtualHostBuilder} that this
     * {@link VirtualHostServiceBindingBuilder} was created from.
     *
     * @throws IllegalStateException if the path that the {@link Service} will be bound to is not specified
     */
    public VirtualHostBuilder build(Service<HttpRequest, HttpResponse> service) {
        build0(service);
        return virtualHostBuilder;
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        virtualHostBuilder.serviceConfigBuilder(serviceConfigBuilder);
    }
}
