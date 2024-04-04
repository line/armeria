/*
 * Copyright 2023 LINE Corporation
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

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.server.RouteDecoratingService;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * Builds {@link ServiceConfig}s for a {@link ServerBuilder}.
 * All {@link ServiceConfig}s built by this builder will be served under the specified context paths.
 *
 * <pre>{@code
 * Server.builder()
 *       .contextPath("/v1", "/v2")
 *       .service(myService) // served under "/v1" and "/v2"
 * }</pre>
 */
@UnstableApi
public final class ContextPathServicesBuilder extends AbstractContextPathServicesBuilder<ServerBuilder> {

    ContextPathServicesBuilder(ServerBuilder parent, VirtualHostBuilder virtualHostBuilder,
                               Set<String> contextPaths) {
        super(parent, virtualHostBuilder, contextPaths);
    }

    /**
     * Configures an {@link HttpService} under the context path with the {@code customizer}.
     */
    public ContextPathServicesBuilder withRoute(
            Consumer<? super ContextPathServiceBindingBuilder> customizer) {
        requireNonNull(customizer, "customizer");
        customizer.accept(new ContextPathServiceBindingBuilder(this));
        return this;
    }

    /**
     * Returns a {@link ContextPathServiceBindingBuilder} which is for binding
     * an {@link HttpService} fluently.
     */
    @Override
    public ContextPathServiceBindingBuilder route() {
        return new ContextPathServiceBindingBuilder(this);
    }

    /**
     * Returns a {@link ContextPathDecoratingBindingBuilder} which is for binding
     * a {@code decorator} fluently.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public ContextPathDecoratingBindingBuilder routeDecorator() {
        return new ContextPathDecoratingBindingBuilder(this);
    }

    @Override
    public ContextPathServicesBuilder serviceUnder(String pathPrefix,
                                                   HttpService service) {
        return (ContextPathServicesBuilder) super.serviceUnder(pathPrefix, service);
    }

    @Override
    public ContextPathServicesBuilder service(String pathPattern, HttpService service) {
        return (ContextPathServicesBuilder) super.service(pathPattern, service);
    }

    @Override
    public ContextPathServicesBuilder service(Route route, HttpService service) {
        return (ContextPathServicesBuilder) super.service(route, service);
    }

    @Override
    public ContextPathServicesBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        return (ContextPathServicesBuilder) super.service(serviceWithRoutes, decorators);
    }

    @SafeVarargs
    @Override
    public final ContextPathServicesBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return (ContextPathServicesBuilder) super.service(serviceWithRoutes, decorators);
    }

    @Override
    public ContextPathServicesBuilder annotatedService(Object service) {
        return (ContextPathServicesBuilder) super.annotatedService(service);
    }

    @Override
    public ContextPathServicesBuilder annotatedService(Object service,
                                                       Object... exceptionHandlersAndConverters) {
        return (ContextPathServicesBuilder) super.annotatedService(service, exceptionHandlersAndConverters);
    }

    @Override
    public ContextPathServicesBuilder annotatedService(
            Object service,
            Function<? super HttpService, ? extends HttpService> decorator,
            Object... exceptionHandlersAndConverters) {
        return (ContextPathServicesBuilder) super.annotatedService(service, decorator,
                                                                   exceptionHandlersAndConverters);
    }

    @Override
    public ContextPathServicesBuilder annotatedService(String pathPrefix,
                                                       Object service) {
        return (ContextPathServicesBuilder) super.annotatedService(pathPrefix, service);
    }

    @Override
    public ContextPathServicesBuilder annotatedService(String pathPrefix, Object service,
                                                       Object... exceptionHandlersAndConverters) {
        return (ContextPathServicesBuilder) super.annotatedService(pathPrefix, service,
                                                                   exceptionHandlersAndConverters);
    }

    @Override
    public ContextPathServicesBuilder annotatedService(
            String pathPrefix, Object service,
            Function<? super HttpService, ? extends HttpService> decorator,
            Object... exceptionHandlersAndConverters) {
        return (ContextPathServicesBuilder) super.annotatedService(
                pathPrefix, service, decorator, exceptionHandlersAndConverters);
    }

    @Override
    public ContextPathServicesBuilder annotatedService(String pathPrefix, Object service,
                                                       Iterable<?> exceptionHandlersAndConverters) {
        return (ContextPathServicesBuilder) super.annotatedService(
                pathPrefix, service, exceptionHandlersAndConverters);
    }

    @Override
    public ContextPathServicesBuilder annotatedService(
            String pathPrefix, Object service,
            Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<?> exceptionHandlersAndConverters) {
        return (ContextPathServicesBuilder) super.annotatedService(pathPrefix, service,
                                                                   decorator, exceptionHandlersAndConverters);
    }

    @Override
    public ContextPathServicesBuilder annotatedService(
            String pathPrefix, Object service,
            Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(decorator, "decorator");
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        return annotatedService().pathPrefix(pathPrefix)
                                 .decorator(decorator)
                                 .exceptionHandlers(exceptionHandlerFunctions)
                                 .requestConverters(requestConverterFunctions)
                                 .responseConverters(responseConverterFunctions)
                                 .build(service);
    }

    @Override
    public ContextPathAnnotatedServiceConfigSetters annotatedService() {
        return new ContextPathAnnotatedServiceConfigSetters(this);
    }

    @Override
    public ContextPathServicesBuilder decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (ContextPathServicesBuilder) super.decorator(decorator);
    }

    @Override
    public ContextPathServicesBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (ContextPathServicesBuilder) super.decorator(decoratingHttpServiceFunction);
    }

    @Override
    public ContextPathServicesBuilder decorator(
            String pathPattern,
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (ContextPathServicesBuilder) super.decorator(pathPattern, decorator);
    }

    @Override
    public ContextPathServicesBuilder decorator(String pathPattern,
                                                DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (ContextPathServicesBuilder) super.decorator(pathPattern, decoratingHttpServiceFunction);
    }

    @Override
    public ContextPathServicesBuilder decorator(
            Route route,
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (ContextPathServicesBuilder) super.decorator(route, decorator);
    }

    @Override
    public ContextPathServicesBuilder decorator(Route route,
                                                DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (ContextPathServicesBuilder) super.decorator(route, decoratingHttpServiceFunction);
    }

    @Override
    public ContextPathServicesBuilder decoratorUnder(
            String prefix,
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (ContextPathServicesBuilder) super.decoratorUnder(prefix, decoratingHttpServiceFunction);
    }

    @Override
    public ContextPathServicesBuilder decoratorUnder(
            String prefix,
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (ContextPathServicesBuilder) super.decoratorUnder(prefix, decorator);
    }

    @Override
    ContextPathServicesBuilder addServiceConfigSetters(
            ServiceConfigSetters serviceConfigSetters) {
        return (ContextPathServicesBuilder) super.addServiceConfigSetters(serviceConfigSetters);
    }

    @Override
    ContextPathServicesBuilder addRouteDecoratingService(
            RouteDecoratingService routeDecoratingService) {
        return (ContextPathServicesBuilder) super.addRouteDecoratingService(routeDecoratingService);
    }
}
