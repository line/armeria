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

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A builder class for binding an {@link HttpService} fluently. This class can be instantiated through
 * {@link ServerBuilder#route()}. You can also configure an {@link HttpService} using
 * {@link ServerBuilder#withRoute(Consumer)}.
 *
 * <p>Call {@link #build(HttpService)} to build the {@link HttpService} and return to the {@link ServerBuilder}.
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
    public ServiceBindingBuilder matchesParams(String... paramPredicates) {
        return (ServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public ServiceBindingBuilder matchesParams(Iterable<String> paramPredicates) {
        return (ServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public ServiceBindingBuilder matchesParams(String paramName, Predicate<? super String> valuePredicate) {
        return (ServiceBindingBuilder) super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public ServiceBindingBuilder matchesHeaders(String... headerPredicates) {
        return (ServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public ServiceBindingBuilder matchesHeaders(Iterable<String> headerPredicates) {
        return (ServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public ServiceBindingBuilder matchesHeaders(CharSequence headerName,
                                                Predicate<? super String> valuePredicate) {
        return (ServiceBindingBuilder) super.matchesHeaders(headerName, valuePredicate);
    }

    @Override
    public ServiceBindingBuilder addRoute(Route route) {
        return (ServiceBindingBuilder) super.addRoute(route);
    }

    @Override
    public ServiceBindingBuilder defaultServiceName(String defaultServiceName) {
        return (ServiceBindingBuilder) super.defaultServiceName(defaultServiceName);
    }

    @Override
    public ServiceBindingBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        return (ServiceBindingBuilder) super.defaultServiceNaming(defaultServiceNaming);
    }

    @Override
    public ServiceBindingBuilder defaultLogName(String defaultLogName) {
        return (ServiceBindingBuilder) super.defaultLogName(defaultLogName);
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
    public ServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        return (ServiceBindingBuilder) super.accessLogFormat(accessLogFormat);
    }

    @Override
    public ServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        return (ServiceBindingBuilder) super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @Override
    public ServiceBindingBuilder decorator(Function<? super HttpService, ? extends HttpService> decorator) {
        return (ServiceBindingBuilder) super.decorator(decorator);
    }

    @Override
    @SafeVarargs
    public final ServiceBindingBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (ServiceBindingBuilder) super.decorators(decorators);
    }

    @Override
    public ServiceBindingBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (ServiceBindingBuilder) super.decorators(decorators);
    }

    /**
     * Sets the {@link HttpService} and returns the {@link ServerBuilder} that this
     * {@link ServiceBindingBuilder} was created from.
     *
     * @throws IllegalStateException if the path that the {@link HttpService} will be bound to is not specified
     */
    public ServerBuilder build(HttpService service) {
        build0(service);
        return serverBuilder;
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        serverBuilder.serviceConfigBuilder(serviceConfigBuilder);
    }
}
