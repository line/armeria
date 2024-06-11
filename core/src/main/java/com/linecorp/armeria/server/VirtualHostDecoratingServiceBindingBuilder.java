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
public final class VirtualHostDecoratingServiceBindingBuilder
        extends AbstractBindingBuilder<VirtualHostDecoratingServiceBindingBuilder> {

    private final VirtualHostBuilder virtualHostBuilder;

    VirtualHostDecoratingServiceBindingBuilder(VirtualHostBuilder virtualHostBuilder) {
        super(EMPTY_CONTEXT_PATHS);
        this.virtualHostBuilder = requireNonNull(virtualHostBuilder, "virtualHostBuilder");
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
                new RouteDecoratingService(route, "/", decorator)));
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
