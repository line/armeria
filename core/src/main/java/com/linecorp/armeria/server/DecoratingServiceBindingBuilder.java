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

import com.linecorp.armeria.internal.server.RouteDecoratingService;

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
public final class DecoratingServiceBindingBuilder
        extends AbstractBindingBuilder<DecoratingServiceBindingBuilder> {

    private final ServerBuilder serverBuilder;

    DecoratingServiceBindingBuilder(ServerBuilder serverBuilder) {
        super(EMPTY_CONTEXT_PATHS);
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
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
                route -> serverBuilder.routingDecorator(new RouteDecoratingService(route, "/", decorator)));
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
