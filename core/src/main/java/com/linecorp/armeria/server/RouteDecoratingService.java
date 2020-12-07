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

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Function;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

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
final class RouteDecoratingService implements HttpService {

    private static final AttributeKey<Queue<HttpService>> DECORATOR_KEY =
            AttributeKey.valueOf(RouteDecoratingService.class, "SERVICE_CHAIN");

    static Function<? super HttpService, InitialDispatcherService> newDecorator(
            Router<RouteDecoratingService> router) {
        return delegate -> new InitialDispatcherService(delegate, router);
    }

    private final Route route;
    private final HttpService decorator;
    private final int order;

    RouteDecoratingService(Route route,
                           Function<? super HttpService, ? extends HttpService> decoratorFunction, int order) {
        this.route = requireNonNull(route, "route");
        decorator = requireNonNull(decoratorFunction, "decoratorFunction").apply(this);
        this.order = order;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Queue<HttpService> delegates = ctx.attr(DECORATOR_KEY);
        assert delegates != null;
        final HttpService delegate = delegates.poll();
        assert delegate != null;
        return delegate.serve(ctx, req);
    }

    Route route() {
        return route;
    }

    private HttpService decorator() {
        return decorator;
    }

    int order() {
        return order;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("route", route)
                          .toString();
    }

    private static class InitialDispatcherService extends SimpleDecoratingHttpService {

        private final Router<RouteDecoratingService> router;

        InitialDispatcherService(HttpService delegate, Router<RouteDecoratingService> router) {
            super(delegate);
            this.router = router;
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

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("router", router)
                              .add("delegate", unwrap()).toString();
        }
    }
}
