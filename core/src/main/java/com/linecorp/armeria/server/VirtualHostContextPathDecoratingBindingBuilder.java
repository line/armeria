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

import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder class for binding a {@code decorator} to a {@link Route} fluently under a set of context paths.
 *
 * <p>Call {@link #build(Function)} or {@link #build(DecoratingHttpServiceFunction)}
 * to build the {@code decorator} and return to the {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 *
 * sb.virtualHost("example.com")
 *   .contextPath("/v1", "/v2")
 *   .routeDecorator()                                // Configure a decorator with route.
 *   .pathPrefix("/api/users")
 *   .build((delegate, ctx, req) -> {
 *       if (!"bearer my_token".equals(req.headers().get(HttpHeaderNames.AUTHORIZATION))) {
 *           return HttpResponse.of(HttpStatus.UNAUTHORIZED);
 *       }
 *       return delegate.serve(ctx, req);
 *   });                                              // Return to the VirtualHostBuilder.
 * }</pre>
 */
@UnstableApi
public final class VirtualHostContextPathDecoratingBindingBuilder
        extends AbstractContextPathDecoratingBindingBuilder<VirtualHostBuilder> {

    VirtualHostContextPathDecoratingBindingBuilder(
            AbstractContextPathServicesBuilder<VirtualHostBuilder> builder) {
        super(builder);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder path(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.path(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder pathPrefix(String prefix) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.pathPrefix(prefix);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder get(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.get(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder post(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.post(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder put(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.put(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder patch(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.patch(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder delete(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.delete(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder options(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.options(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder head(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.head(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder trace(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.trace(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder connect(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.connect(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder methods(HttpMethod... methods) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.methods(methods);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder methods(
            Iterable<HttpMethod> methods) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.methods(methods);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder consumes(MediaType... consumeTypes) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder consumes(
            Iterable<MediaType> consumeTypes) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder produces(MediaType... produceTypes) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder produces(
            Iterable<MediaType> produceTypes) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder matchesParams(
            String... paramPredicates) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder matchesParams(
            Iterable<String> paramPredicates) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder matchesParams(
            String paramName,
            Predicate<? super String> valuePredicate) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder matchesHeaders(
            String... headerPredicates) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder matchesHeaders(
            Iterable<String> headerPredicates) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder matchesHeaders(
            CharSequence headerName, Predicate<? super String> valuePredicate) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.matchesHeaders(headerName,
                                                                                     valuePredicate);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder addRoute(Route route) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.addRoute(route);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder exclude(String pathPattern) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.exclude(pathPattern);
    }

    @Override
    public VirtualHostContextPathDecoratingBindingBuilder exclude(Route excludedRoute) {
        return (VirtualHostContextPathDecoratingBindingBuilder) super.exclude(excludedRoute);
    }

    /**
     * Sets the {@code decorator} and returns the {@link VirtualHostContextPathServicesBuilder}
     * that this {@link VirtualHostContextPathDecoratingBindingBuilder} was created from.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}
     */
    @Override
    public VirtualHostContextPathServicesBuilder build(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (VirtualHostContextPathServicesBuilder) super.build(decorator);
    }

    /**
     * Sets the {@link DecoratingHttpServiceFunction} and returns the
     * {@link VirtualHostContextPathServicesBuilder} that this
     * {@link VirtualHostContextPathDecoratingBindingBuilder} was created from.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}
     */
    @Override
    public VirtualHostContextPathServicesBuilder build(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (VirtualHostContextPathServicesBuilder) super.build(decoratingHttpServiceFunction);
    }
}
