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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.server.ServerBuilder.decorate;
import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.internal.server.RouteDecoratingService;
import com.linecorp.armeria.internal.server.RouteUtil;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExtensions;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

abstract class AbstractContextPathServicesBuilder<SELF extends AbstractContextPathServicesBuilder<SELF, T>,
        T extends ServiceConfigsBuilder<T>> implements ServiceConfigsBuilder<SELF> {

    private final Set<String> contextPaths;
    private final T parent;
    private final VirtualHostBuilder virtualHostBuilder;

    AbstractContextPathServicesBuilder(T parent, VirtualHostBuilder virtualHostBuilder,
                                       Set<String> contextPaths) {
        checkArgument(!contextPaths.isEmpty(), "At least one context path is required");
        for (String contextPath: contextPaths) {
            RouteUtil.ensureAbsolutePath(contextPath, "contextPath");
        }

        this.parent = parent;
        this.contextPaths = ImmutableSet.copyOf(contextPaths);
        this.virtualHostBuilder = virtualHostBuilder;
    }

    @SuppressWarnings("unchecked")
    final SELF self() {
        return (SELF) this;
    }

    /**
     * Binds the specified {@link HttpService} under the specified context path.
     * If the specified {@link HttpService} is an {@link HttpServiceWithRoutes}, the {@code pathPrefix} is added
     * to each {@link Route} of {@link HttpServiceWithRoutes#routes()}. For example, the
     * {@code serviceWithRoutes} in the following code will be bound to
     * ({@code "/v1/foo/bar"}) and ({@code "/v1/foo/baz"}):
     * <pre>{@code
     * > HttpServiceWithRoutes serviceWithRoutes = new HttpServiceWithRoutes() {
     * >     @Override
     * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) { ... }
     * >
     * >     @Override
     * >     public Set<Route> routes() {
     * >         return Set.of(Route.builder().path("/bar").build(),
     * >                       Route.builder().path("/baz").build());
     * >     }
     * > };
     * >
     * > Server.builder()
     * >       .contextPath("/v1")
     * >       .serviceUnder("/foo", serviceWithRoutes)
     * >       .build();
     * }</pre>
     */
    @Override
    public SELF serviceUnder(String pathPrefix, HttpService service) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        final HttpServiceWithRoutes serviceWithRoutes = service.as(HttpServiceWithRoutes.class);
        if (serviceWithRoutes != null) {
            serviceWithRoutes.routes().forEach(route -> {
                for (String contextPath: contextPaths) {
                    final ServiceConfigBuilder serviceConfigBuilder =
                            new ServiceConfigBuilder(route.withPrefix(pathPrefix), contextPath, service);
                    serviceConfigBuilder.addMappedRoute(route);
                    addServiceConfigSetters(serviceConfigBuilder);
                }
            });
        } else {
            service(Route.builder().pathPrefix(pathPrefix).build(), service);
        }
        return self();
    }

    /**
     * Binds the specified {@link HttpService} at the specified path pattern under the context path.
     * e.g.
     * <ul>
     *   <li>{@code /login} (no path parameters)</li>
     *   <li>{@code /users/{userId}} (curly-brace style)</li>
     *   <li>{@code /list/:productType/by/:ordering} (colon style)</li>
     *   <li>{@code exact:/foo/bar} (exact match)</li>
     *   <li>{@code prefix:/files} (prefix match)</li>
     *   <li><code>glob:/~&#42;/downloads/**</code> (glob pattern)</li>
     *   <li>{@code regex:^/files/(?<filePath>.*)$} (regular expression)</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    @Override
    public SELF service(String pathPattern, HttpService service) {
        return service(Route.builder().path(pathPattern).build(), service);
    }

    /**
     * Binds the specified {@link HttpService} at the specified {@link Route} under the context path.
     */
    @Override
    public SELF service(Route route, HttpService service) {
        for (String contextPath: contextPaths) {
            addServiceConfigSetters(new ServiceConfigBuilder(route, contextPath, service));
        }
        return self();
    }

    /**
     * Decorates and binds the specified {@link HttpServiceWithRoutes} at multiple {@link Route}s
     * under the context path.
     *
     * @param serviceWithRoutes the {@link HttpServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    @Override
    public SELF service(HttpServiceWithRoutes serviceWithRoutes,
                        Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        requireNonNull(serviceWithRoutes, "serviceWithRoutes");
        requireNonNull(serviceWithRoutes.routes(), "serviceWithRoutes.routes()");
        requireNonNull(decorators, "decorators");

        final HttpService decorated = decorate(serviceWithRoutes, decorators);
        serviceWithRoutes.routes().forEach(route -> service(route, decorated));
        return self();
    }

    /**
     * Decorates and binds the specified {@link HttpServiceWithRoutes} at multiple {@link Route}s
     * under the context path.
     *
     * @param serviceWithRoutes the {@link HttpServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    @Override
    public SELF service(
            HttpServiceWithRoutes serviceWithRoutes,
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return service(serviceWithRoutes, ImmutableList.copyOf(requireNonNull(decorators, "decorators")));
    }

    /**
     * Binds the specified annotated service object under the context path.
     */
    @Override
    public SELF annotatedService(Object service) {
        return annotatedService("/", service, Function.identity(), ImmutableList.of());
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public SELF annotatedService(Object service, Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, Function.identity(),
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public SELF annotatedService(
            Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    @Override
    public SELF annotatedService(String pathPrefix, Object service) {
        return annotatedService(pathPrefix, service, Function.identity(), ImmutableList.of());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public SELF annotatedService(String pathPrefix, Object service, Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(),
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    @Override
    public SELF annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public SELF annotatedService(
            String pathPrefix, Object service, Iterable<?> exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(),
                                requireNonNull(exceptionHandlersAndConverters,
                                               "exceptionHandlersAndConverters"));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    @Override
    public SELF annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<?> exceptionHandlersAndConverters) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(decorator, "decorator");
        requireNonNull(exceptionHandlersAndConverters, "exceptionHandlersAndConverters");
        final AnnotatedServiceExtensions configurator =
                AnnotatedServiceExtensions
                        .ofExceptionHandlersAndConverters(exceptionHandlersAndConverters);
        return annotatedService(pathPrefix, service, decorator, configurator.exceptionHandlers(),
                                configurator.requestConverters(), configurator.responseConverters());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlerFunctions the {@link ExceptionHandlerFunction}s
     * @param requestConverterFunctions the {@link RequestConverterFunction}s
     * @param responseConverterFunctions the {@link ResponseConverterFunction}s
     */
    @Override
    public abstract SELF annotatedService(
            String pathPrefix,
            Object service,
            Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions);

    /**
     * Decorates all {@link HttpService}s with the specified {@code decorator}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}s
     */
    @Override
    public SELF decorator(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.ofCatchAll(), decorator);
    }

    /**
     * Decorates all {@link HttpService}s with the specified {@link DecoratingHttpServiceFunction}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public SELF decorator(DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.ofCatchAll(), decoratingHttpServiceFunction);
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@code pathPattern}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public SELF decorator(String pathPattern, Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.builder().path(pathPattern).build(), decorator);
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@code pathPattern}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public SELF decorator(String pathPattern, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.builder().path(pathPattern).build(), decoratingHttpServiceFunction);
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public SELF decorator(Route route, Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(route, "route");
        requireNonNull(decorator, "decorator");
        for (String contextPath: contextPaths) {
            addRouteDecoratingService(new RouteDecoratingService(route, contextPath, decorator));
        }
        return self();
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@link Route}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param route the route being decorated
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public SELF decorator(Route route, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        requireNonNull(decoratingHttpServiceFunction, "decoratingHttpServiceFunction");
        return decorator(route, delegate -> new FunctionalDecoratingHttpService(
                delegate, decoratingHttpServiceFunction));
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public SELF decoratorUnder(String prefix, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.builder().pathPrefix(prefix).build(), decoratingHttpServiceFunction);
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public SELF decoratorUnder(String prefix, Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.builder().pathPrefix(prefix).build(), decorator);
    }

    SELF addServiceConfigSetters(ServiceConfigSetters<?> serviceConfigSetters) {
        virtualHostBuilder.addServiceConfigSetters(serviceConfigSetters);
        return self();
    }

    SELF addRouteDecoratingService(RouteDecoratingService routeDecoratingService) {
        virtualHostBuilder.addRouteDecoratingService(routeDecoratingService);
        return self();
    }

    /**
     * Returns the parent {@link T}.
     */
    public T and() {
        return parent;
    }

    final Set<String> contextPaths() {
        return contextPaths;
    }
}
