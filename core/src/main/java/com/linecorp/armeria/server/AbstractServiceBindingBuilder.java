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
import java.util.function.Supplier;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.netty.channel.EventLoopGroup;

/**
 * A builder class for binding an {@link HttpService} fluently.
 *
 * @see ServiceBindingBuilder
 * @see VirtualHostServiceBindingBuilder
 */
abstract class AbstractServiceBindingBuilder<SELF extends AbstractServiceBindingBuilder<SELF>>
        extends AbstractBindingBuilder<SELF>
        implements ServiceConfigSetters<AbstractServiceBindingBuilder<SELF>> {

    private final DefaultServiceConfigSetters defaultServiceConfigSetters = new DefaultServiceConfigSetters();

    AbstractServiceBindingBuilder(Set<String> contextPaths) {
        super(contextPaths);
    }

    @SuppressWarnings("unchecked")
    @Override
    final SELF self() {
        return (SELF) this;
    }

    @Override
    public SELF requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    @Override
    public SELF requestTimeoutMillis(long requestTimeoutMillis) {
        defaultServiceConfigSetters.requestTimeoutMillis(requestTimeoutMillis);
        return self();
    }

    @Override
    public SELF maxRequestLength(long maxRequestLength) {
        defaultServiceConfigSetters.maxRequestLength(maxRequestLength);
        return self();
    }

    @Override
    public SELF verboseResponses(boolean verboseResponses) {
        defaultServiceConfigSetters.verboseResponses(verboseResponses);
        return self();
    }

    @Override
    public SELF accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
                               true);
    }

    @Override
    public SELF accessLogWriter(AccessLogWriter accessLogWriter,
                                boolean shutdownOnStop) {
        defaultServiceConfigSetters.accessLogWriter(accessLogWriter, shutdownOnStop);
        return self();
    }

    @Override
    public SELF decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        defaultServiceConfigSetters.decorator(decorator);
        return self();
    }

    @Override
    public SELF decorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return self();
    }

    @Override
    public SELF decorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        defaultServiceConfigSetters.decorators(decorators);
        return self();
    }

    @Override
    public SELF defaultServiceName(String defaultServiceName) {
        defaultServiceConfigSetters.defaultServiceName(defaultServiceName);
        return self();
    }

    @Override
    public SELF defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        defaultServiceConfigSetters.defaultServiceNaming(defaultServiceNaming);
        return self();
    }

    @Override
    public SELF defaultLogName(String defaultLogName) {
        defaultServiceConfigSetters.defaultLogName(defaultLogName);
        return self();
    }

    @Override
    public SELF blockingTaskExecutor(ScheduledExecutorService blockingTaskExecutor,
                                     boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return self();
    }

    @Override
    public SELF blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                     boolean shutdownOnStop) {
        defaultServiceConfigSetters.blockingTaskExecutor(blockingTaskExecutor, shutdownOnStop);
        return self();
    }

    @Override
    public SELF blockingTaskExecutor(int numThreads) {
        defaultServiceConfigSetters.blockingTaskExecutor(numThreads);
        return self();
    }

    @Override
    public SELF successFunction(SuccessFunction successFunction) {
        defaultServiceConfigSetters.successFunction(successFunction);
        return self();
    }

    @Override
    public SELF requestAutoAbortDelay(Duration delay) {
        defaultServiceConfigSetters.requestAutoAbortDelay(delay);
        return self();
    }

    @Override
    public SELF requestAutoAbortDelayMillis(long delayMillis) {
        defaultServiceConfigSetters.requestAutoAbortDelayMillis(delayMillis);
        return self();
    }

    @Override
    public SELF multipartUploadsLocation(Path multipartUploadsLocation) {
        defaultServiceConfigSetters.multipartUploadsLocation(multipartUploadsLocation);
        return self();
    }

    @Override
    public SELF multipartRemovalStrategy(MultipartRemovalStrategy removalStrategy) {
        defaultServiceConfigSetters.multipartRemovalStrategy(removalStrategy);
        return self();
    }

    @Override
    public SELF serviceWorkerGroup(EventLoopGroup serviceWorkerGroup,
                                   boolean shutdownOnStop) {
        defaultServiceConfigSetters.serviceWorkerGroup(serviceWorkerGroup, shutdownOnStop);
        return self();
    }

    @Override
    public SELF serviceWorkerGroup(int numThreads) {
        defaultServiceConfigSetters.serviceWorkerGroup(numThreads);
        return self();
    }

    @Override
    public SELF requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        defaultServiceConfigSetters.requestIdGenerator(requestIdGenerator);
        return self();
    }

    @Override
    public SELF addHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.addHeader(name, value);
        return self();
    }

    @Override
    public SELF addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.addHeaders(defaultHeaders);
        return self();
    }

    @Override
    public SELF setHeader(CharSequence name, Object value) {
        defaultServiceConfigSetters.setHeader(name, value);
        return self();
    }

    @Override
    public SELF setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        defaultServiceConfigSetters.setHeaders(defaultHeaders);
        return self();
    }

    @Override
    public SELF errorHandler(ServiceErrorHandler serviceErrorHandler) {
        defaultServiceConfigSetters.errorHandler(serviceErrorHandler);
        return self();
    }

    @Override
    public SELF contextHook(Supplier<? extends AutoCloseable> contextHook) {
        defaultServiceConfigSetters.contextHook(contextHook);
        return self();
    }

    abstract void serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder);

    final void build0(HttpService service) {
        final ServiceWithRoutes<?, ?> serviceWithRoutes = service.as(ServiceWithRoutes.class);
        final Set<Route> fallbackRoutes =
                firstNonNull(serviceWithRoutes != null ? serviceWithRoutes.routes() : null,
                             ImmutableSet.of());

        final List<Route> routes = buildRouteList(fallbackRoutes);
        final HttpService decoratedService = defaultServiceConfigSetters.decorator().apply(service);
        for (String contextPath : contextPaths()) {
            for (Route route : routes) {
                final ServiceConfigBuilder serviceConfigBuilder =
                        defaultServiceConfigSetters.toServiceConfigBuilder(
                                route, contextPath, decoratedService);
                serviceConfigBuilder(serviceConfigBuilder);
            }
        }
    }

    final void build0(HttpService service, Route mappedRoute) {
        final List<Route> routes = buildRouteList(ImmutableSet.of());
        assert routes.size() == 1; // Only one route is set via addRoute().
        final HttpService decoratedService = defaultServiceConfigSetters.decorator().apply(service);
        final ServiceConfigBuilder serviceConfigBuilder =
                defaultServiceConfigSetters.toServiceConfigBuilder(routes.get(0), "/", decoratedService);
        serviceConfigBuilder.addMappedRoute(mappedRoute);
        serviceConfigBuilder(serviceConfigBuilder);
    }
}
