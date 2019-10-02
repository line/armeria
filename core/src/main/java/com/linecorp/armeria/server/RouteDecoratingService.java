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
 * Decorates a {@link Service} whose {@link Route} matches the {@link #route}.
 *
 * {@link RouteDecoratingService} is used for binding your {@link Service} to multiple {@code decorator}s
 * with {@link Route}s. e.g.
 * <pre>{@code
 * > Server server = new ServerBuilder()
 * >     .service("/api/users",  userService)
 * >     .decoratorUnder("/", loggingDecorator)
 * >     .decoratorUnder("/api", authDecorator)
 * >     .decoratorUnder("/api/users", traceDecorator)
 * >     .build();
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
 *   <li>Finally, put the original {@link Service} at the end of {@code pendingDecorators}</li>
 * </ul>
 *
 * <p>The request will go through the below decorators to reach the {@code userService}.
 * <pre>{@code
 *  request -> initialDispatcherService
 *          -> loggingDecorator         -> routeDecoratingService
 *          -> authDecorator            -> routeDecoratingService
 *          -> traceDecorator           -> routeDecoratingService
 *          -> userService
 * }</pre>
 */
final class RouteDecoratingService implements HttpService {

    private static final AttributeKey<Queue<Service<HttpRequest, HttpResponse>>> DECORATOR_KEY =
            AttributeKey.valueOf(RouteDecoratingService.class, "SERVICE_CHAIN");

    static Function<Service<HttpRequest, HttpResponse>,
            Service<HttpRequest, HttpResponse>> newDecorator(Router<RouteDecoratingService> router) {
        return delegate -> new InitialDispatcherService(delegate, router);
    }

    private final Route route;
    private final Service<HttpRequest, HttpResponse> decorator;

    RouteDecoratingService(Route route,
                           Function<Service<HttpRequest, HttpResponse>,
                                   ? extends Service<HttpRequest, HttpResponse>> decoratorFunction) {
        this.route = requireNonNull(route, "route");
        decorator = requireNonNull(decoratorFunction, "decoratorFunction").apply(this);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Queue<Service<HttpRequest, HttpResponse>> delegates = ctx.attr(DECORATOR_KEY).get();
        final Service<HttpRequest, HttpResponse> delegate = delegates.poll();
        return delegate.serve(ctx, req);
    }

    Route route() {
        return route;
    }

    private Service<HttpRequest, HttpResponse> decorator() {
        return decorator;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("route", route)
                          .add("decorator", decorator).toString();
    }

    private static class InitialDispatcherService extends SimpleDecoratingHttpService {

        private final Router<RouteDecoratingService> router;

        InitialDispatcherService(Service<HttpRequest, HttpResponse> delegate,
                                 Router<RouteDecoratingService> router) {
            super(delegate);
            this.router = router;
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final Queue<Service<HttpRequest, HttpResponse>> serviceChain = new ArrayDeque<>(4);
            router.findAll(ctx.routingContext()).forEach(routed -> {
                if (routed.isPresent()) {
                    serviceChain.add(routed.value().decorator());
                }
            });

            if (serviceChain.isEmpty()) {
                return delegate().serve(ctx, req);
            }
            serviceChain.add(delegate());
            final Service<HttpRequest, HttpResponse> service = serviceChain.poll();
            ctx.attr(DECORATOR_KEY).set(serviceChain);
            return service.serve(ctx, req);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("router", router)
                              .add("delegate", delegate()).toString();
        }
    }
}
