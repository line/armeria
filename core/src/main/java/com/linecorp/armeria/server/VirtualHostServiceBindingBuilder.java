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
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A builder class for binding an {@link HttpService} fluently. This class can be instantiated through
 * {@link VirtualHostBuilder#route()}. You can also configure an {@link HttpService} using
 * {@link VirtualHostBuilder#withRoute(Consumer)}.
 *
 * <p>Call {@link #build(HttpService)} to build the {@link HttpService} and return to the
 * {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * sb.virtualHost("example.com")
 *   .route()                                      // Configure the first service in "example.com".
 *   .post("/foo/bar")
 *   .consumes(JSON, PLAIN_TEXT_UTF_8)
 *   .produces(JSON_UTF_8)
 *   .requestTimeoutMillis(5000)
 *   .maxRequestLength(8192)
 *   .verboseResponses(true)
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
    public VirtualHostServiceBindingBuilder matchesParams(String... paramPredicates) {
        return (VirtualHostServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public VirtualHostServiceBindingBuilder matchesParams(Iterable<String> paramPredicates) {
        return (VirtualHostServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public VirtualHostServiceBindingBuilder matchesParams(String paramName,
                                                          Predicate<? super String> valuePredicate) {
        return (VirtualHostServiceBindingBuilder) super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public VirtualHostServiceBindingBuilder matchesHeaders(String... headerPredicates) {
        return (VirtualHostServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public VirtualHostServiceBindingBuilder matchesHeaders(Iterable<String> headerPredicates) {
        return (VirtualHostServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public VirtualHostServiceBindingBuilder matchesHeaders(CharSequence headerName,
                                                           Predicate<? super String> valuePredicate) {
        return (VirtualHostServiceBindingBuilder) super.matchesHeaders(headerName, valuePredicate);
    }

    @Override
    public VirtualHostServiceBindingBuilder addRoute(Route route) {
        return (VirtualHostServiceBindingBuilder) super.addRoute(route);
    }

    @Override
    public VirtualHostServiceBindingBuilder exclude(String pathPattern) {
        return (VirtualHostServiceBindingBuilder) super.exclude(pathPattern);
    }

    @Override
    public VirtualHostServiceBindingBuilder exclude(Route excludedRoute) {
        return (VirtualHostServiceBindingBuilder) super.exclude(excludedRoute);
    }

    @Override
    public VirtualHostServiceBindingBuilder defaultServiceName(String defaultServiceName) {
        return (VirtualHostServiceBindingBuilder) super.defaultServiceName(defaultServiceName);
    }

    @Override
    public VirtualHostServiceBindingBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        return (VirtualHostServiceBindingBuilder) super.defaultServiceNaming(defaultServiceNaming);
    }

    @Override
    public VirtualHostServiceBindingBuilder defaultLogName(String defaultLogName) {
        return (VirtualHostServiceBindingBuilder) super.defaultLogName(defaultLogName);
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
    public VirtualHostServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        return (VirtualHostServiceBindingBuilder) super.accessLogFormat(accessLogFormat);
    }

    @Override
    public VirtualHostServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                            boolean shutdownOnStop) {
        return (VirtualHostServiceBindingBuilder) super.accessLogWriter(accessLogWriter, shutdownOnStop);
    }

    @Override
    public VirtualHostServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (VirtualHostServiceBindingBuilder) super.decorator(decorator);
    }

    @Override
    @SafeVarargs
    public final VirtualHostServiceBindingBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (VirtualHostServiceBindingBuilder) super.decorators(decorators);
    }

    @Override
    public VirtualHostServiceBindingBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (VirtualHostServiceBindingBuilder) super.decorators(decorators);
    }

    @Override
    public VirtualHostServiceBindingBuilder blockingTaskExecutor(
            ScheduledExecutorService blockingTaskExecutor,
            boolean shutdownOnStop) {
        return (VirtualHostServiceBindingBuilder) super.blockingTaskExecutor(blockingTaskExecutor,
                                                                             shutdownOnStop);
    }

    @Override
    public VirtualHostServiceBindingBuilder blockingTaskExecutor(int numThreads) {
        return (VirtualHostServiceBindingBuilder) super.blockingTaskExecutor(numThreads);
    }

    @Override
    public VirtualHostServiceBindingBuilder successFunction(
            BiPredicate<? super RequestContext, ? super RequestLog> successFunction) {
        return (VirtualHostServiceBindingBuilder) super.successFunction(successFunction);
    }

    /**
     * Sets the {@link HttpService} and returns the {@link VirtualHostBuilder} that this
     * {@link VirtualHostServiceBindingBuilder} was created from.
     *
     * @throws IllegalStateException if the path that the {@link HttpService} will be bound to is not specified
     */
    public VirtualHostBuilder build(HttpService service) {
        build0(service);
        return virtualHostBuilder;
    }

    @Override
    void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        virtualHostBuilder.addServiceConfigSetters(serviceConfigBuilder);
    }
}
