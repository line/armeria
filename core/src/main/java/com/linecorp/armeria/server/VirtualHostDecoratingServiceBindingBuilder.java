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

import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.common.DecoratorAndOrder;

/**
 * A builder class for binding a {@code decorator} to a {@link Route} fluently.
 * This class can be instantiated through {@link VirtualHostBuilder#routeDecorator()}.
 *
 * <p>Call {@link #build(Function)} or {@link #build(DecoratingHttpServiceFunction)}
 * to build the {@code decorator} and return to the {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 *
 * sb.virtualHost("example.com")
 *   .routeDecorator()                                // Configure a decorator with route.
 *   .pathPrefix("/api/users")
 *   .build((delegate, ctx, req) -> {
 *       if (!"bearer my_token".equals(req.headers().get(HttpHeaderNames.AUTHORIZATION))) {
 *           return HttpResponse.of(HttpStatus.UNAUTHORIZED);
 *       }
 *       return delegate.serve(ctx, req);
 *   });                                              // Return to the VirtualHostBuilder.
 * }</pre>
 *
 * @see VirtualHostServiceBindingBuilder
 */
public final class VirtualHostDecoratingServiceBindingBuilder extends AbstractBindingBuilder {

    private final VirtualHostBuilder virtualHostBuilder;
    private int order = DecoratorAndOrder.DEFAULT_ORDER;

    VirtualHostDecoratingServiceBindingBuilder(VirtualHostBuilder virtualHostBuilder) {
        this.virtualHostBuilder = requireNonNull(virtualHostBuilder, "virtualHostBuilder");
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder path(String pathPattern) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.path(pathPattern);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder pathPrefix(String prefix) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.pathPrefix(prefix);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder methods(HttpMethod... methods) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder methods(Iterable<HttpMethod> methods) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder get(String pathPattern) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.get(pathPattern);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder post(String pathPattern) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.post(pathPattern);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder put(String pathPattern) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.put(pathPattern);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder patch(String pathPattern) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.patch(pathPattern);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder delete(String pathPattern) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.delete(pathPattern);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder options(String pathPattern) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.delete(pathPattern);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder head(String pathPattern) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.head(pathPattern);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder trace(String pathPattern) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.trace(pathPattern);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder connect(String pathPattern) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.connect(pathPattern);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder consumes(MediaType... consumeTypes) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder consumes(Iterable<MediaType> consumeTypes) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder produces(MediaType... produceTypes) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder produces(Iterable<MediaType> produceTypes) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder matchesParams(String... paramPredicates) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder matchesParams(Iterable<String> paramPredicates) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder matchesParams(String paramName,
                                                                    Predicate<? super String> valuePredicate) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder matchesHeaders(String... headerPredicates) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder matchesHeaders(Iterable<String> headerPredicates) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder matchesHeaders(CharSequence headerName,
                                                                     Predicate<? super String> valuePredicate) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.matchesHeaders(headerName, valuePredicate);
    }

    @Override
    public VirtualHostDecoratingServiceBindingBuilder addRoute(Route route) {
        return (VirtualHostDecoratingServiceBindingBuilder) super.addRoute(route);
    }

    /**
     * Sets the order.
     */
    public VirtualHostDecoratingServiceBindingBuilder order(int order) {
        this.order = order;
        return this;
    }

    /**
     * Sets the {@code decorator} and returns {@link VirtualHostBuilder} that this
     * {@link VirtualHostDecoratingServiceBindingBuilder} was created from.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}
     */
    public VirtualHostBuilder build(Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(decorator, "decorator");
        buildRouteList().forEach(route -> virtualHostBuilder.addRouteDecoratingService(
                new RouteDecoratingService(route, decorator, order)));
        return virtualHostBuilder;
    }

    /**
     * Sets the {@link DecoratingHttpServiceFunction} and returns {@link VirtualHostBuilder} that this
     * {@link VirtualHostDecoratingServiceBindingBuilder} was created from.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}
     */
    public VirtualHostBuilder build(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        requireNonNull(decoratingHttpServiceFunction, "decoratingHttpServiceFunction");
        return build(delegate -> new FunctionalDecoratingHttpService(delegate, decoratingHttpServiceFunction));
    }
}
