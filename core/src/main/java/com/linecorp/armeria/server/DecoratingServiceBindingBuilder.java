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

/**
 * A builder class for binding a {@code decorator} with {@link Route} fluently.
 * This class can be instantiated through {@link ServerBuilder#routeDecorator()}.
 *
 * <p>Call {@link #build(Function)} or {@link #build(DecoratingHttpServiceFunction)}
 * to build the {@code decorator} and return to the {@link ServerBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 *
 * sb.routeDecorator()                                // Configure a decorator with route.
 *   .pathPrefix("/api/users")
 *   .build((delegate, ctx, req) -> {
 *       if (!"bearer my_token".equals(req.headers().get(HttpHeaderNames.AUTHORIZATION))) {
 *           return HttpResponse.of(HttpStatus.UNAUTHORIZED);
 *       }
 *       return delegate.serve(ctx, req);
 *   });                                              // Return to the ServerBuilder.
 * }</pre>
 *
 * @see VirtualHostDecoratingServiceBindingBuilder
 */
public final class DecoratingServiceBindingBuilder extends AbstractBindingBuilder {

    private final ServerBuilder serverBuilder;

    DecoratingServiceBindingBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    @Override
    public DecoratingServiceBindingBuilder path(String pathPattern) {
        return (DecoratingServiceBindingBuilder) super.path(pathPattern);
    }

    @Override
    public DecoratingServiceBindingBuilder pathPrefix(String prefix) {
        return (DecoratingServiceBindingBuilder) super.pathPrefix(prefix);
    }

    @Override
    public DecoratingServiceBindingBuilder methods(HttpMethod... methods) {
        return (DecoratingServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public DecoratingServiceBindingBuilder methods(Iterable<HttpMethod> methods) {
        return (DecoratingServiceBindingBuilder) super.methods(methods);
    }

    @Override
    public DecoratingServiceBindingBuilder get(String pathPattern) {
        return (DecoratingServiceBindingBuilder) super.get(pathPattern);
    }

    @Override
    public DecoratingServiceBindingBuilder post(String pathPattern) {
        return (DecoratingServiceBindingBuilder) super.post(pathPattern);
    }

    @Override
    public DecoratingServiceBindingBuilder put(String pathPattern) {
        return (DecoratingServiceBindingBuilder) super.put(pathPattern);
    }

    @Override
    public DecoratingServiceBindingBuilder patch(String pathPattern) {
        return (DecoratingServiceBindingBuilder) super.patch(pathPattern);
    }

    @Override
    public DecoratingServiceBindingBuilder delete(String pathPattern) {
        return (DecoratingServiceBindingBuilder) super.delete(pathPattern);
    }

    @Override
    public DecoratingServiceBindingBuilder options(String pathPattern) {
        return (DecoratingServiceBindingBuilder) super.delete(pathPattern);
    }

    @Override
    public DecoratingServiceBindingBuilder head(String pathPattern) {
        return (DecoratingServiceBindingBuilder) super.head(pathPattern);
    }

    @Override
    public DecoratingServiceBindingBuilder trace(String pathPattern) {
        return (DecoratingServiceBindingBuilder) super.trace(pathPattern);
    }

    @Override
    public DecoratingServiceBindingBuilder connect(String pathPattern) {
        return (DecoratingServiceBindingBuilder) super.connect(pathPattern);
    }

    @Override
    public DecoratingServiceBindingBuilder consumes(MediaType... consumeTypes) {
        return (DecoratingServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public DecoratingServiceBindingBuilder consumes(Iterable<MediaType> consumeTypes) {
        return (DecoratingServiceBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public DecoratingServiceBindingBuilder produces(MediaType... produceTypes) {
        return (DecoratingServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public DecoratingServiceBindingBuilder produces(Iterable<MediaType> produceTypes) {
        return (DecoratingServiceBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public DecoratingServiceBindingBuilder matchesParams(String... paramPredicates) {
        return (DecoratingServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public DecoratingServiceBindingBuilder matchesParams(Iterable<String> paramPredicates) {
        return (DecoratingServiceBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public DecoratingServiceBindingBuilder matchesParams(String paramName,
                                                         Predicate<? super String> valuePredicate) {
        return (DecoratingServiceBindingBuilder) super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public DecoratingServiceBindingBuilder matchesHeaders(String... headerPredicates) {
        return (DecoratingServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public DecoratingServiceBindingBuilder matchesHeaders(Iterable<String> headerPredicates) {
        return (DecoratingServiceBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public DecoratingServiceBindingBuilder matchesHeaders(CharSequence headerName,
                                                          Predicate<? super String> valuePredicate) {
        return (DecoratingServiceBindingBuilder) super.matchesHeaders(headerName, valuePredicate);
    }

    @Override
    public DecoratingServiceBindingBuilder addRoute(Route route) {
        return (DecoratingServiceBindingBuilder) super.addRoute(route);
    }

    @Override
    public DecoratingServiceBindingBuilder exclude(Route excludedRoute) {
        return (DecoratingServiceBindingBuilder) super.exclude(excludedRoute);
    }

    /**
     * Sets the {@code decorator} and returns {@link ServerBuilder} that this
     * {@link DecoratingServiceBindingBuilder} was created from.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}
     */
    public ServerBuilder build(Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(decorator, "decorator");
        buildRouteList().forEach(
                route -> serverBuilder.routingDecorator(new RouteDecoratingService(route, decorator)));
        return serverBuilder;
    }

    /**
     * Sets the {@link DecoratingHttpServiceFunction} and returns {@link ServerBuilder} that this
     * {@link DecoratingServiceBindingBuilder} was created from.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}
     */
    public ServerBuilder build(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        requireNonNull(decoratingHttpServiceFunction, "decoratingHttpServiceFunction");
        return build(delegate -> new FunctionalDecoratingHttpService(delegate, decoratingHttpServiceFunction));
    }
}
