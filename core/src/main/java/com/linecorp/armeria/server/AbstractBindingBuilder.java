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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.HttpMethod.CONNECT;
import static com.linecorp.armeria.common.HttpMethod.DELETE;
import static com.linecorp.armeria.common.HttpMethod.GET;
import static com.linecorp.armeria.common.HttpMethod.HEAD;
import static com.linecorp.armeria.common.HttpMethod.OPTIONS;
import static com.linecorp.armeria.common.HttpMethod.PATCH;
import static com.linecorp.armeria.common.HttpMethod.POST;
import static com.linecorp.armeria.common.HttpMethod.PUT;
import static com.linecorp.armeria.common.HttpMethod.TRACE;
import static com.linecorp.armeria.server.HttpHeaderUtil.ensureUniqueMediaTypes;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.annotation.MatchesHeader;
import com.linecorp.armeria.server.annotation.MatchesParam;

/**
 * An abstract builder class for binding something to a {@link Route} fluently.
 */
abstract class AbstractBindingBuilder<SELF extends AbstractBindingBuilder<SELF>> {
    static final Set<String> EMPTY_CONTEXT_PATHS = ImmutableSet.of("/");

    private Set<HttpMethod> methods = ImmutableSet.of();
    private Set<MediaType> consumeTypes = ImmutableSet.of();
    private Set<MediaType> produceTypes = ImmutableSet.of();
    private final List<RoutingPredicate<QueryParams>> paramPredicates = new ArrayList<>();
    private final List<RoutingPredicate<HttpHeaders>> headerPredicates = new ArrayList<>();
    private final Map<RouteBuilder, Set<HttpMethod>> routeBuilders = new LinkedHashMap<>();
    private final Set<RouteBuilder> pathBuilders = new LinkedHashSet<>();
    private final List<Route> additionalRoutes = new ArrayList<>();
    private final List<Route> excludedRoutes = new ArrayList<>();
    private final Set<String> contextPaths;

    AbstractBindingBuilder(Set<String> contextPaths) {
        this.contextPaths = contextPaths;
    }

    Set<String> contextPaths() {
        return contextPaths;
    }

    @SuppressWarnings("unchecked")
    SELF self() {
        return (SELF) this;
    }

    /**
     * Sets the path pattern that an {@link HttpService} will be bound to.
     * Please refer to the <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * in order to learn how to specify a path pattern.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF path(String pathPattern) {
        pathBuilders.add(Route.builder().path(requireNonNull(pathPattern, "pathPattern")));
        return self();
    }

    /**
     * Sets the specified prefix which is a directory that an {@link HttpService} will be bound under.
     * {@code pathPrefix("/my/path")} is identical to {@code path("prefix:/my/path")}.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF pathPrefix(String prefix) {
        pathBuilders.add(Route.builder().pathPrefix(requireNonNull(prefix, "prefix")));
        return self();
    }

    /**
     * Sets the path pattern that an {@link HttpService} will be bound to, only supporting
     * {@link HttpMethod#GET} requests.
     * Please refer to the <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * in order to learn how to specify a path pattern.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF get(String pathPattern) {
        addRouteBuilder(pathPattern, GET);
        return self();
    }

    /**
     * Sets the path pattern that an {@link HttpService} will be bound to, only supporting
     * {@link HttpMethod#POST} requests.
     * Please refer to the <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * in order to learn how to specify a path pattern.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF post(String pathPattern) {
        addRouteBuilder(pathPattern, POST);
        return self();
    }

    /**
     * Sets the path pattern that an {@link HttpService} will be bound to, only supporting
     * {@link HttpMethod#PUT} requests.
     * Please refer to the <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * in order to learn how to specify a path pattern.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF put(String pathPattern) {
        addRouteBuilder(pathPattern, PUT);
        return self();
    }

    /**
     * Sets the path pattern that an {@link HttpService} will be bound to, only supporting
     * {@link HttpMethod#PATCH} requests.
     * Please refer to the <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * in order to learn how to specify a path pattern.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF patch(String pathPattern) {
        addRouteBuilder(pathPattern, PATCH);
        return self();
    }

    /**
     * Sets the path pattern that an {@link HttpService} will be bound to, only supporting
     * {@link HttpMethod#DELETE} requests.
     * Please refer to the <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * in order to learn how to specify a path pattern.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF delete(String pathPattern) {
        addRouteBuilder(pathPattern, DELETE);
        return self();
    }

    /**
     * Sets the path pattern that an {@link HttpService} will be bound to, only supporting
     * {@link HttpMethod#OPTIONS} requests.
     * Please refer to the <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * in order to learn how to specify a path pattern.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF options(String pathPattern) {
        addRouteBuilder(pathPattern, OPTIONS);
        return self();
    }

    /**
     * Sets the path pattern that an {@link HttpService} will be bound to, only supporting
     * {@link HttpMethod#HEAD} requests.
     * Please refer to the <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * in order to learn how to specify a path pattern.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF head(String pathPattern) {
        addRouteBuilder(pathPattern, HEAD);
        return self();
    }

    /**
     * Sets the path pattern that an {@link HttpService} will be bound to, only supporting
     * {@link HttpMethod#TRACE} requests.
     * Please refer to the <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * in order to learn how to specify a path pattern.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF trace(String pathPattern) {
        addRouteBuilder(pathPattern, TRACE);
        return self();
    }

    /**
     * Sets the path pattern that an {@link HttpService} will be bound to, only supporting
     * {@link HttpMethod#CONNECT} requests.
     * Please refer to the <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * in order to learn how to specify a path pattern.
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public SELF connect(String pathPattern) {
        addRouteBuilder(pathPattern, CONNECT);
        return self();
    }

    private void addRouteBuilder(String pathPattern, HttpMethod method) {
        addRouteBuilder(Route.builder().path(requireNonNull(pathPattern, "pathPattern")), EnumSet.of(method));
    }

    private void addRouteBuilder(RouteBuilder routeBuilder, Set<HttpMethod> methods) {
        final Set<HttpMethod> methodSet = routeBuilders.computeIfAbsent(
                routeBuilder, key -> EnumSet.noneOf(HttpMethod.class));

        for (HttpMethod method : methods) {
            if (!methodSet.add(method)) {
                throw new IllegalArgumentException("duplicate HTTP method: " + method +
                                                   ", for: " + routeBuilder);
            }
        }
    }

    /**
     * Sets the {@link HttpMethod}s that an {@link HttpService} will support. If not set,
     * {@link HttpMethod#knownMethods()}s are set.
     *
     * @see #path(String)
     * @see #pathPrefix(String)
     */
    public SELF methods(HttpMethod... methods) {
        return methods(ImmutableSet.copyOf(requireNonNull(methods, "methods")));
    }

    /**
     * Sets the {@link HttpMethod}s that an {@link HttpService} will support. If not set,
     * {@link HttpMethod#knownMethods()}s are set.
     *
     * @see #path(String)
     * @see #pathPrefix(String)
     */
    public SELF methods(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        checkArgument(!Iterables.isEmpty(methods), "methods can't be empty.");
        this.methods = Sets.immutableEnumSet(methods);
        return self();
    }

    /**
     * Sets {@link MediaType}s that an {@link HttpService} will consume. If not set, the {@link HttpService}
     * will accept all media types.
     */
    public SELF consumes(MediaType... consumeTypes) {
        consumes(ImmutableSet.copyOf(requireNonNull(consumeTypes, "consumeTypes")));
        return self();
    }

    /**
     * Sets {@link MediaType}s that an {@link HttpService} will consume. If not set, the {@link HttpService}
     * will accept all media types.
     */
    public SELF consumes(Iterable<MediaType> consumeTypes) {
        ensureUniqueMediaTypes(consumeTypes, "consumeTypes");
        this.consumeTypes = ImmutableSet.copyOf(consumeTypes);
        return self();
    }

    /**
     * Sets {@link MediaType}s that an {@link HttpService} will produce to be used in
     * content negotiation. See <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.2">Accept header</a>
     * for more information.
     */
    public SELF produces(MediaType... produceTypes) {
        produces(ImmutableSet.copyOf(requireNonNull(produceTypes, "produceTypes")));
        return self();
    }

    /**
     * Sets {@link MediaType}s that an {@link HttpService} will produce to be used in
     * content negotiation. See <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.2">Accept header</a>
     * for more information.
     */
    public SELF produces(Iterable<MediaType> produceTypes) {
        ensureUniqueMediaTypes(produceTypes, "produceTypes");
        this.produceTypes = ImmutableSet.copyOf(produceTypes);
        return self();
    }

    /**
     * Sets the {@link Route} to accept a request if it matches all the specified predicates for
     * HTTP parameters. The predicate can be one of the following forms:
     * <ul>
     *     <li>{@code some-param=some-value} which means that the request must have a
     *     {@code some-param=some-value} parameter</li>
     *     <li>{@code some-param!=some-value} which means that the request must not have a
     *     {@code some-param=some-value} parameter</li>
     *     <li>{@code some-param} which means that the request must contain a {@code some-param} parameter</li>
     *     <li>{@code !some-param} which means that the request must not contain a {@code some-param}
     *     parameter</li>
     * </ul>
     *
     * <p>Note that these predicates can be evaluated only with the query string of the request URI.
     * Also note that each predicate will be evaluated with the decoded value of HTTP parameters,
     * so do not use percent-encoded value in the predicate.
     *
     * @see MatchesParam
     */
    public SELF matchesParams(String... paramPredicates) {
        return matchesParams(ImmutableList.copyOf(requireNonNull(paramPredicates, "paramPredicates")));
    }

    /**
     * Sets the {@link Route} to accept a request if it matches all the specified predicates for
     * HTTP parameters. The predicate can be one of the following forms:
     * <ul>
     *     <li>{@code some-param=some-value} which means that the request must have a
     *     {@code some-param=some-value} parameter</li>
     *     <li>{@code some-param!=some-value} which means that the request must not have a
     *     {@code some-param=some-value} parameter</li>
     *     <li>{@code some-param} which means that the request must contain a {@code some-param} parameter</li>
     *     <li>{@code !some-param} which means that the request must not contain a {@code some-param}
     *     parameter</li>
     * </ul>
     *
     * <p>Note that these predicates can be evaluated only with the query string of the request URI.
     * Also note that each predicate will be evaluated with the decoded value of HTTP parameters,
     * so do not use percent-encoded value in the predicate.
     *
     * @see MatchesParam
     */
    public SELF matchesParams(Iterable<String> paramPredicates) {
        this.paramPredicates.addAll(RoutingPredicate.copyOfParamPredicates(
                requireNonNull(paramPredicates, "paramPredicates")));
        return self();
    }

    /**
     * Sets the {@link Route} to accept a request when the specified {@code valuePredicate} evaluates
     * {@code true} with the value of the specified {@code paramName} parameter.
     */
    public SELF matchesParams(String paramName, Predicate<? super String> valuePredicate) {
        requireNonNull(paramName, "paramName");
        requireNonNull(valuePredicate, "valuePredicate");
        paramPredicates.add(RoutingPredicate.ofParams(paramName, valuePredicate));
        return self();
    }

    /**
     * Sets the {@link Route} to accept a request if it matches all the specified predicates for
     * {@link HttpHeaders}. The predicate can be one of the following forms:
     * <ul>
     *     <li>{@code some-header=some-value} which means that the request must have a
     *     {@code some-header: some-value} header</li>
     *     <li>{@code some-header!=some-value} which means that the request must not have a
     *     {@code some-header: some-value} header</li>
     *     <li>{@code some-header} which means that the request must contain a {@code some-header} header</li>
     *     <li>{@code !some-header} which means that the request must not contain a {@code some-header}
     *     header</li>
     * </ul>
     *
     * @see MatchesHeader
     */
    public SELF matchesHeaders(String... headerPredicates) {
        return matchesHeaders(ImmutableList.copyOf(requireNonNull(headerPredicates, "headerPredicates")));
    }

    /**
     * Sets the {@link Route} to accept a request if it matches all the specified predicates for
     * {@link HttpHeaders}. The predicate can be one of the following forms:
     * <ul>
     *     <li>{@code some-header=some-value} which means that the request must have a
     *     {@code some-header: some-value} header</li>
     *     <li>{@code some-header!=some-value} which means that the request must not have a
     *     {@code some-header: some-value} an header</li>
     *     <li>{@code some-header} which means that the request must contain a {@code some-header} header</li>
     *     <li>{@code !some-header} which means that the request must not contain a {@code some-header}
     *     header</li>
     * </ul>
     *
     * @see MatchesHeader
     */
    public SELF matchesHeaders(Iterable<String> headerPredicates) {
        this.headerPredicates.addAll(RoutingPredicate.copyOfHeaderPredicates(
                requireNonNull(headerPredicates, "headerPredicates")));
        return self();
    }

    /**
     * Sets the {@link Route} to accept a request when the specified {@code valuePredicate} evaluates
     * {@code true} with the value of the specified {@code headerName} header.
     */
    public SELF matchesHeaders(CharSequence headerName,
                               Predicate<? super String> valuePredicate) {
        requireNonNull(headerName, "headerName");
        requireNonNull(valuePredicate, "valuePredicate");
        headerPredicates.add(RoutingPredicate.ofHeaders(headerName, valuePredicate));
        return self();
    }

    /**
     * Specifies an additional {@link Route} that should be matched.
     */
    public SELF addRoute(Route route) {
        additionalRoutes.add(requireNonNull(route, "route"));
        return self();
    }

    /**
     * Adds a {@code pathPattern} that is supposed to be excluded from the {@link Route}s built by this
     * {@link AbstractBindingBuilder}.
     * Please refer to <a href="https://armeria.dev/docs/server-basics#path-patterns">Path patterns</a>
     * to learn more about path pattern syntax.
     */
    public SELF exclude(String pathPattern) {
        requireNonNull(pathPattern, "pathPattern");
        excludedRoutes.add(Route.builder().path(pathPattern).build());
        return self();
    }

    /**
     * Adds a {@link Route} that is supposed to be excluded from the {@link Route}s built by this
     * {@link AbstractBindingBuilder}.
     */
    public SELF exclude(Route excludedRoute) {
        excludedRoutes.add(requireNonNull(excludedRoute, "excludedRoute"));
        return self();
    }

    /**
     * Returns a newly-created {@link Route}s based on the properties of this builder.
     */
    final List<Route> buildRouteList() {
        return buildRouteList(ImmutableSet.of());
    }

    /**
     * Returns a newly-created {@link Route}s based on the properties of this builder.
     *
     * @param fallbackRoutes the {@link Route}s to use when a user did not specify any {@link Route}s.
     */
    final List<Route> buildRouteList(Collection<Route> fallbackRoutes) {
        final ImmutableList.Builder<Route> builder = ImmutableList.builder();

        if (additionalRoutes.isEmpty()) {
            if (pathBuilders.isEmpty() && routeBuilders.isEmpty()) {
                if (fallbackRoutes.isEmpty()) {
                    throw new IllegalStateException(
                            "Should set at least one path that the service is bound to before calling this.");
                } else {
                    // Use the fallbackRoutes since a user didn't specify any routes.
                    return builder.addAll(fallbackRoutes).build();
                }
            }
            if (pathBuilders.isEmpty() && !methods.isEmpty()) {
                throw new IllegalStateException("Should set a path when the methods are set: " + methods);
            }
        }

        if (!pathBuilders.isEmpty()) {
            final Set<HttpMethod> pathMethods = methods.isEmpty() ? HttpMethod.knownMethods() : methods;
            pathBuilders.forEach(pathBuilder -> addRouteBuilder(pathBuilder, pathMethods));
        }

        routeBuilders.forEach((routeBuilder, routeMethods) -> {
            builder.add(routeBuilder.methods(routeMethods)
                                    .consumes(consumeTypes)
                                    .produces(produceTypes)
                                    .matchesParams(paramPredicates)
                                    .matchesHeaders(headerPredicates)
                                    .exclude(excludedRoutes)
                                    .build());
        });

        additionalRoutes.forEach(builder::add);

        return builder.build();
    }
}
