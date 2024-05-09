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
package com.linecorp.armeria.internal.server;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Function;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Routed;
import com.linecorp.armeria.server.Router;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.VirtualHostBuilder;

import io.netty.util.AttributeKey;

/**
 * Decorates an {@link HttpService} whose {@link Route} matches the {@link #route}.
 *
 * {@link RouteDecoratingService} is used for binding your {@link HttpService} to multiple {@code decorator}s
 * with {@link Route}s. e.g.
 * <pre>{@code
 * > Server server =
 * >     Server.builder()
 * >           .service("/api/users", userService)
 * >           .decoratorUnder("/", loggingDecorator)
 * >           .decoratorUnder("/api", authDecorator)
 * >           .decoratorUnder("/api/users", traceDecorator)
 * >           .build();
 * }</pre>
 *
 * {@link VirtualHostBuilder} wraps each specified {@code decorator} with {@link RouteDecoratingService} and
 * merges them into a {@link Router}. When a client requests to {@code /api/users/1},
 * {@link InitialDispatcherService} will work the following steps for building decorators
 * being matched to the given request:
 *
 * <ul>
 *   <li>Finds all matched decorators from {@link InitialDispatcherService#router}
 *       using {@link RoutingContext}</li>
 *   <li>Put them into {@code pendingDecorators} with keeping the finding order</li>
 *   <li>Finally, put the original {@link HttpService} at the end of {@code pendingDecorators}</li>
 * </ul>
 *
 * <p>The request will go through the below decorators to reach the {@code userService}.
 * <pre>{@code
 *  request -> initialDispatcherService
 *          -> traceDecorator           -> routeDecoratingService
 *          -> authDecorator            -> routeDecoratingService
 *          -> loggingDecorator         -> routeDecoratingService
 *          -> userService
 * }</pre>
 */
public final class RouteDecoratingService implements HttpService {

    private static final AttributeKey<Queue<HttpService>> DECORATOR_KEY =
            AttributeKey.valueOf(RouteDecoratingService.class, "SERVICE_CHAIN");

    public static Function<? super HttpService, HttpService> newDecorator(
            Router<RouteDecoratingService> router, List<RouteDecoratingService> routeDecoratingServices) {
        return delegate -> new InitialDispatcherService(delegate, router, routeDecoratingServices);
    }

    private final Route route;
    private final HttpService decorator;

    @Nullable
    private VirtualHost defaultVirtualHost;

    public RouteDecoratingService(Route route, String contextPath,
                                  Function<? super HttpService, ? extends HttpService> decoratorFunction) {
        this.route = requireNonNull(route, "route").withPrefix(contextPath);
        decorator = requireNonNull(decoratorFunction, "decoratorFunction").apply(this);
    }

    private RouteDecoratingService(Route route, HttpService decorator) {
        this.route = requireNonNull(route, "route");
        this.decorator = requireNonNull(decorator, "decorator");
    }

    /**
     * Adds the specified {@code prefix} to this {@code decorator}.
     */
    public RouteDecoratingService withRoutePrefix(String prefix) {
        return new RouteDecoratingService(route.withPrefix(prefix), decorator);
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        final VirtualHost defaultVirtualHost = cfg.server().config().defaultVirtualHost();
        if (this.defaultVirtualHost == defaultVirtualHost) {
            // This condition is necessary because:
            // - The same decorator can be added multiple times.
            // - Avoid infinite loop. The delegate of `decorator` is this.
            return;
        }
        this.defaultVirtualHost = defaultVirtualHost;
        decorator.serviceAdded(cfg);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Queue<HttpService> delegates = ctx.attr(DECORATOR_KEY);
        assert delegates != null;
        final HttpService delegate = delegates.poll();
        assert delegate != null;
        return delegate.serve(ctx, req);
    }

    public Route route() {
        return route;
    }

    private HttpService decorator() {
        return decorator;
    }

    @Nullable
    public <T extends HttpService> T as(ServiceRequestContext ctx, Class<T> serviceClass) {
        final Queue<HttpService> delegates = ctx.attr(DECORATOR_KEY);
        if (delegates == null) {
            return null;
        }

        for (HttpService delegate : delegates) {
            final T service = delegate.as(serviceClass);
            if (service != null) {
                return service;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("route", route)
                          .toString();
    }

    public static final class InitialDispatcherService extends SimpleDecoratingHttpService {

        private final Router<RouteDecoratingService> router;
        private final List<RouteDecoratingService> routeDecoratingServices;

        InitialDispatcherService(HttpService delegate, Router<RouteDecoratingService> router,
                                 List<RouteDecoratingService> routeDecoratingServices) {
            super(delegate);
            this.router = router;
            this.routeDecoratingServices = routeDecoratingServices;
        }

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            super.serviceAdded(cfg);
            for (RouteDecoratingService routeDecoratingService : routeDecoratingServices) {
                routeDecoratingService.serviceAdded(cfg);
            }
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final Queue<HttpService> serviceChain = new ArrayDeque<>(4);
            router.findAll(ctx.routingContext()).forEach(routed -> {
                if (routed.isPresent()) {
                    serviceChain.add(routed.value().decorator());
                }
            });

            if (serviceChain.isEmpty()) {
                return unwrap().serve(ctx, req);
            }
            serviceChain.add((HttpService) unwrap());
            final HttpService service = serviceChain.poll();
            ctx.setAttr(DECORATOR_KEY, serviceChain);
            assert service != null;
            return service.serve(ctx, req);
        }

        @Nullable
        public <T extends HttpService> T findService(ServiceRequestContext ctx,
                                                     Class<? extends T> serviceClass) {
            for (Routed<RouteDecoratingService> routed : router.findAll(ctx.routingContext())) {
                if (routed.isPresent()) {
                    final T service = routed.value().decorator().as(serviceClass);
                    if (service != null) {
                        return service;
                    }
                }
            }
            return unwrap().as(serviceClass);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("router", router)
                              .add("delegate", unwrap()).toString();
        }
    }
}
