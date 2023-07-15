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
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.server.RouteDecoratingService;

/**
 * A builder class for binding a {@code decorator} with {@link Route} fluently
 * under a set of context paths.
 *
 * <p>Call {@link #build(Function)} or {@link #build(DecoratingHttpServiceFunction)}
 * to build the {@code decorator}.
 *
 * <pre>{@code
 * Server.builder()
 *       .contextPath("/v1", "/v2")
 *       .routeDecorator()
 *       .pathPrefix("/api")
 *       .path("/decorated")
 *       .build(myDecorator) // decorator under /v1/api/decorated, /v2/api/decorated
 * }</pre>
 */
@UnstableApi
public final class ContextPathDecoratingBindingBuilder<T extends ServiceConfigsBuilder>
        extends AbstractBindingBuilder {

    private final ContextPathServicesBuilder<T> builder;
    private final Set<String> contextPaths;

    ContextPathDecoratingBindingBuilder(ContextPathServicesBuilder<T> builder,
                                        Set<String> contextPaths) {
        this.builder = builder;
        this.contextPaths = contextPaths;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> path(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.path(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> pathPrefix(String prefix) {
        return (ContextPathDecoratingBindingBuilder<T>) super.pathPrefix(prefix);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> get(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.get(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> post(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.post(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> put(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.put(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> patch(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.patch(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> delete(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.delete(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> options(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.options(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> head(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.head(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> trace(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.trace(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> connect(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.connect(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> methods(HttpMethod... methods) {
        return (ContextPathDecoratingBindingBuilder<T>) super.methods(methods);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> methods(Iterable<HttpMethod> methods) {
        return (ContextPathDecoratingBindingBuilder<T>) super.methods(methods);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> consumes(MediaType... consumeTypes) {
        return (ContextPathDecoratingBindingBuilder<T>) super.consumes(consumeTypes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> consumes(Iterable<MediaType> consumeTypes) {
        return (ContextPathDecoratingBindingBuilder<T>) super.consumes(consumeTypes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> produces(MediaType... produceTypes) {
        return (ContextPathDecoratingBindingBuilder<T>) super.produces(produceTypes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> produces(Iterable<MediaType> produceTypes) {
        return (ContextPathDecoratingBindingBuilder<T>) super.produces(produceTypes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesParams(String... paramPredicates) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesParams(paramPredicates);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesParams(Iterable<String> paramPredicates) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesParams(paramPredicates);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesParams(
            String paramName, Predicate<? super String> valuePredicate) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesParams(paramName, valuePredicate);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesHeaders(String... headerPredicates) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesHeaders(headerPredicates);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesHeaders(Iterable<String> headerPredicates) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesHeaders(headerPredicates);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> matchesHeaders(CharSequence headerName,
                                                                 Predicate<? super String> valuePredicate) {
        return (ContextPathDecoratingBindingBuilder<T>) super.matchesHeaders(headerName, valuePredicate);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> addRoute(Route route) {
        return (ContextPathDecoratingBindingBuilder<T>) super.addRoute(route);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> exclude(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder<T>) super.exclude(pathPattern);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ContextPathDecoratingBindingBuilder<T> exclude(Route excludedRoute) {
        return (ContextPathDecoratingBindingBuilder<T>) super.exclude(excludedRoute);
    }

    /**
     * Sets the {@code decorator} and returns the (ContextPathDecoratingBindingBuilder)
     * that this {@link ContextPathDecoratingBindingBuilder} was created from.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}
     */
    public ContextPathServicesBuilder<T> build(
            Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(decorator, "decorator");
        buildRouteList().forEach(
                route -> contextPaths.forEach(contextPath -> builder.addRouteDecoratingService(
                        new RouteDecoratingService(route, contextPath, decorator))));
        return builder;
    }

    /**
     * Sets the {@link DecoratingHttpServiceFunction} and returns the {@link ContextPathServicesBuilder}
     * that this {@link ContextPathDecoratingBindingBuilder} was created from.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}
     */
    public ContextPathServicesBuilder<T> build(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        requireNonNull(decoratingHttpServiceFunction, "decoratingHttpServiceFunction");
        return build(delegate -> new FunctionalDecoratingHttpService(delegate, decoratingHttpServiceFunction));
    }
}
