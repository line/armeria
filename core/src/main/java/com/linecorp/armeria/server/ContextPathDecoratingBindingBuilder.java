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
public final class ContextPathDecoratingBindingBuilder
        extends AbstractContextPathDecoratingBindingBuilder<ServerBuilder> {

    ContextPathDecoratingBindingBuilder(AbstractContextPathServicesBuilder<ServerBuilder> builder) {
        super(builder);
    }

    @Override
    public ContextPathDecoratingBindingBuilder path(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.path(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder pathPrefix(String prefix) {
        return (ContextPathDecoratingBindingBuilder) super.pathPrefix(prefix);
    }

    @Override
    public ContextPathDecoratingBindingBuilder get(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.get(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder post(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.post(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder put(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.put(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder patch(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.patch(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder delete(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.delete(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder options(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.options(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder head(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.head(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder trace(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.trace(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder connect(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.connect(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder methods(HttpMethod... methods) {
        return (ContextPathDecoratingBindingBuilder) super.methods(methods);
    }

    @Override
    public ContextPathDecoratingBindingBuilder methods(Iterable<HttpMethod> methods) {
        return (ContextPathDecoratingBindingBuilder) super.methods(methods);
    }

    @Override
    public ContextPathDecoratingBindingBuilder consumes(MediaType... consumeTypes) {
        return (ContextPathDecoratingBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public ContextPathDecoratingBindingBuilder consumes(
            Iterable<MediaType> consumeTypes) {
        return (ContextPathDecoratingBindingBuilder) super.consumes(consumeTypes);
    }

    @Override
    public ContextPathDecoratingBindingBuilder produces(MediaType... produceTypes) {
        return (ContextPathDecoratingBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public ContextPathDecoratingBindingBuilder produces(
            Iterable<MediaType> produceTypes) {
        return (ContextPathDecoratingBindingBuilder) super.produces(produceTypes);
    }

    @Override
    public ContextPathDecoratingBindingBuilder matchesParams(String... paramPredicates) {
        return (ContextPathDecoratingBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public ContextPathDecoratingBindingBuilder matchesParams(
            Iterable<String> paramPredicates) {
        return (ContextPathDecoratingBindingBuilder) super.matchesParams(paramPredicates);
    }

    @Override
    public ContextPathDecoratingBindingBuilder matchesParams(String paramName,
                                                             Predicate<? super String> valuePredicate) {
        return (ContextPathDecoratingBindingBuilder) super.matchesParams(paramName, valuePredicate);
    }

    @Override
    public ContextPathDecoratingBindingBuilder matchesHeaders(
            String... headerPredicates) {
        return (ContextPathDecoratingBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public ContextPathDecoratingBindingBuilder matchesHeaders(
            Iterable<String> headerPredicates) {
        return (ContextPathDecoratingBindingBuilder) super.matchesHeaders(headerPredicates);
    }

    @Override
    public ContextPathDecoratingBindingBuilder matchesHeaders(CharSequence headerName,
                                                              Predicate<? super String> valuePredicate) {
        return (ContextPathDecoratingBindingBuilder) super.matchesHeaders(headerName, valuePredicate);
    }

    @Override
    public ContextPathDecoratingBindingBuilder addRoute(Route route) {
        return (ContextPathDecoratingBindingBuilder) super.addRoute(route);
    }

    @Override
    public ContextPathDecoratingBindingBuilder exclude(String pathPattern) {
        return (ContextPathDecoratingBindingBuilder) super.exclude(pathPattern);
    }

    @Override
    public ContextPathDecoratingBindingBuilder exclude(Route excludedRoute) {
        return (ContextPathDecoratingBindingBuilder) super.exclude(excludedRoute);
    }

    /**
     * Sets the {@code decorator} and returns the {@link ContextPathServicesBuilder}
     * that this {@link ContextPathDecoratingBindingBuilder} was created from.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}
     */
    @Override
    public ContextPathServicesBuilder build(
            Function<? super HttpService, ? extends HttpService> decorator) {
        return (ContextPathServicesBuilder) super.build(decorator);
    }

    /**
     * Sets the {@link DecoratingHttpServiceFunction} and returns the {@link ContextPathServicesBuilder}
     * that this {@link ContextPathDecoratingBindingBuilder} was created from.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}
     */
    @Override
    public ContextPathServicesBuilder build(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return (ContextPathServicesBuilder) super.build(decoratingHttpServiceFunction);
    }
}
