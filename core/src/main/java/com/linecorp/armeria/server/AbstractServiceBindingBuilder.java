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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * A builder class for binding an {@link HttpService} fluently.
 *
 * @see ServiceBindingBuilder
 * @see VirtualHostServiceBindingBuilder
 */
abstract class AbstractServiceBindingBuilder extends AbstractBindingBuilder implements ServiceConfigSetters {

    private final DefaultServiceConfigSetters defaultServiceConfigSetters = new DefaultServiceConfigSetters();

    @Override
    public AbstractServiceBindingBuilder requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    @Override
    public AbstractServiceBindingBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        defaultServiceConfigSetters.requestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder maxRequestLength(long maxRequestLength) {
        defaultServiceConfigSetters.maxRequestLength(maxRequestLength);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder verboseResponses(boolean verboseResponses) {
        defaultServiceConfigSetters.verboseResponses(verboseResponses);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
                               true);
    }

    @Override
    public AbstractServiceBindingBuilder accessLogWriter(AccessLogWriter accessLogWriter,
                                                         boolean shutdownOnStop) {
        defaultServiceConfigSetters.accessLogWriter(accessLogWriter, shutdownOnStop);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (AbstractServiceBindingBuilder) ServiceConfigSetters.super.decorator(
                decoratingHttpServiceFunction);
    }

    @Override
    public AbstractServiceBindingBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder defaultServiceName(String defaultServiceName) {
        defaultServiceConfigSetters.defaultServiceName(defaultServiceName);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        defaultServiceConfigSetters.defaultServiceNaming(defaultServiceNaming);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder defaultLogName(String defaultLogName) {
        defaultServiceConfigSetters.defaultLogName(defaultLogName);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder blockingTaskExecutor(ScheduledExecutorService blockingTaskExecutor,
                                                              boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                                              boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder blockingTaskExecutor(int numThreads) {
        defaultServiceConfigSetters.blockingTaskExecutor(numThreads);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder successFunction(SuccessFunction successFunction) {
        defaultServiceConfigSetters.successFunction(successFunction);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder requestAutoAbortDelay(Duration delay) {
        defaultServiceConfigSetters.requestAutoAbortDelay(delay);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder requestAutoAbortDelayMillis(long delayMillis) {
        defaultServiceConfigSetters.requestAutoAbortDelayMillis(delayMillis);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder multipartUploadsLocation(Path multipartUploadsLocation) {
        defaultServiceConfigSetters.multipartUploadsLocation(multipartUploadsLocation);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        defaultServiceConfigSetters.requestIdGenerator(requestIdGenerator);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder addHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.addHeader(name, value);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.addHeaders(defaultHeaders);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder setHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.setHeader(name, value);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.setHeaders(defaultHeaders);
        return this;
    }

    @Override
    public AbstractServiceBindingBuilder errorHandler(ServiceErrorHandler serviceErrorHandler) {
        defaultServiceConfigSetters.errorHandler(serviceErrorHandler);
        return this;
    }

    abstract void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder);

    final void build0(HttpService service) {
        final ServiceWithRoutes<?, ?> serviceWithRoutes = service.as(ServiceWithRoutes.class);
        final Set<Route> fallbackRoutes =
                firstNonNull(serviceWithRoutes != null ? serviceWithRoutes.routes() : null,
                             ImmutableSet.of());

        final List<Route> routes = buildRouteList(fallbackRoutes);
        final HttpService decoratedService = defaultServiceConfigSetters.decorator().apply(service);
        for (Route route : routes) {
            final ServiceConfigBuilder serviceConfigBuilder =
                    defaultServiceConfigSetters.toServiceConfigBuilder(route, decoratedService);
            serviceConfigBuilder(serviceConfigBuilder);
        }
    }

    final void build0(HttpService service, Route mappedRoute) {
        final List<Route> routes = buildRouteList(ImmutableSet.of());
        assert routes.size() == 1; // Only one route is set via addRoute().
        final HttpService decoratedService = defaultServiceConfigSetters.decorator().apply(service);
        final ServiceConfigBuilder serviceConfigBuilder =
                defaultServiceConfigSetters.toServiceConfigBuilder(routes.get(0), decoratedService);
        serviceConfigBuilder.addMappedRoute(mappedRoute);
        serviceConfigBuilder(serviceConfigBuilder);
    }
}
