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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.server.RoutingResult.HIGHEST_SCORE;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultRoute implements Route {

    private final PathMapping pathMapping;
    private final Set<HttpMethod> methods;
    private final Set<MediaType> consumes;
    private final Set<MediaType> produces;
    private final List<RoutingPredicate<QueryParams>> paramPredicates;
    private final List<RoutingPredicate<HttpHeaders>> headerPredicates;
    private final boolean isFallback;
    private final List<Route> excludedRoutes;

    private final int hashCode;
    private final int complexity;

    DefaultRoute(PathMapping pathMapping, Set<HttpMethod> methods,
                 Set<MediaType> consumes, Set<MediaType> produces,
                 List<RoutingPredicate<QueryParams>> paramPredicates,
                 List<RoutingPredicate<HttpHeaders>> headerPredicates,
                 boolean isFallback, List<Route> excludedRoutes) {
        this.pathMapping = requireNonNull(pathMapping, "pathMapping");
        checkArgument(!requireNonNull(methods, "methods").isEmpty(), "methods is empty.");
        this.methods = Sets.immutableEnumSet(methods);
        this.consumes = ImmutableSet.copyOf(requireNonNull(consumes, "consumes"));
        this.produces = ImmutableSet.copyOf(requireNonNull(produces, "produces"));
        this.paramPredicates = ImmutableList.copyOf(requireNonNull(paramPredicates, "paramPredicates"));
        this.headerPredicates = ImmutableList.copyOf(requireNonNull(headerPredicates, "headerPredicates"));
        this.isFallback = isFallback;
        // Mark excluded routes as 'fallback' in order to avoid deferring an exception while checking
        // whether a request is matched by 'excludedRoutes'.
        this.excludedRoutes = requireNonNull(excludedRoutes, "excludedRoutes")
                .stream().map(excludedRoute -> excludedRoute.toBuilder().fallback(true).build())
                .collect(toImmutableList());

        hashCode = Objects.hash(this.pathMapping, this.methods, this.consumes, this.produces,
                                this.paramPredicates, this.headerPredicates, this.isFallback,
                                this.excludedRoutes);

        int complexity = 0;
        if (!consumes.isEmpty()) {
            complexity += 1;
        }
        if (!produces.isEmpty()) {
            complexity += 1 << 1;
        }
        if (!paramPredicates.isEmpty()) {
            complexity += 1 << 2;
        }
        if (!headerPredicates.isEmpty()) {
            complexity += 1 << 3;
        }
        // Exclude excludedRoutes from the complexity so that it isn't used when finding duplicate route.
        this.complexity = complexity;
    }

    @Override
    public RoutingResult apply(RoutingContext routingCtx, boolean isRouteDecorator) {
        final RoutingResultBuilder builder = pathMapping.apply(requireNonNull(routingCtx, "routingCtx"));
        if (builder == null) {
            return RoutingResult.empty();
        }

        if (!methods.contains(routingCtx.method())) {
            if (isRouteDecorator) {
                return RoutingResult.empty();
            }
            // '415 Unsupported Media Type' and '406 Not Acceptable' is more specific than
            // '405 Method Not Allowed'. So 405 would be set if there is no status code set before.
            if (routingCtx.deferredStatusException() == null) {
                deferStatusException(routingCtx, HttpStatus.METHOD_NOT_ALLOWED);
            }
            return emptyOrCorsPreflightResult(routingCtx, builder);
        }

        final MediaType contentType = routingCtx.contentType();
        boolean contentTypeMatched = false;
        if (contentType == null) {
            if (consumes.isEmpty()) {
                contentTypeMatched = true;
            }
        } else if (!consumes.isEmpty()) {
            for (MediaType consumeType : consumes) {
                contentTypeMatched = contentType.belongsTo(consumeType);
                if (contentTypeMatched) {
                    break;
                }
            }
            if (!contentTypeMatched) {
                if (isRouteDecorator) {
                    return RoutingResult.empty();
                }
                deferStatusException(routingCtx, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                return emptyOrCorsPreflightResult(routingCtx, builder);
            }
        }

        final List<MediaType> acceptTypes = routingCtx.acceptTypes();
        if (acceptTypes.isEmpty()) {
            if (contentTypeMatched && produces.isEmpty()) {
                builder.score(HIGHEST_SCORE);
            }
            for (MediaType produceType : produces) {
                if (!isAnyType(produceType)) {
                    builder.negotiatedResponseMediaType(produceType);
                    break;
                }
            }
        } else if (!produces.isEmpty()) {
            boolean found = false;
            for (MediaType produceType : produces) {
                for (int i = 0; i < acceptTypes.size(); i++) {
                    final MediaType acceptType = acceptTypes.get(i);
                    if (produceType.belongsTo(acceptType)) {
                        // To early stop path mapping traversal,
                        // we set the score as the best score when the index is 0.

                        final int score = i == 0 ? HIGHEST_SCORE : -1 * i;
                        builder.score(score);
                        if (!isAnyType(produceType)) {
                            builder.negotiatedResponseMediaType(produceType);
                        }
                        found = true;
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }
            if (!found) {
                if (isRouteDecorator) {
                    return RoutingResult.empty();
                }
                deferStatusException(routingCtx, HttpStatus.NOT_ACCEPTABLE);
                return emptyOrCorsPreflightResult(routingCtx, builder);
            }
        }

        if (routingCtx.requiresMatchingParamsPredicates()) {
            if (!paramPredicates.isEmpty()) {
                for (RoutingPredicate<QueryParams> p : paramPredicates) {
                    if (!p.test(routingCtx.params())) {
                        return RoutingResult.empty();
                    }
                }
            }
        }
        if (routingCtx.requiresMatchingHeadersPredicates()) {
            if (!headerPredicates.isEmpty()) {
                for (RoutingPredicate<HttpHeaders> p : headerPredicates) {
                    if (!p.test(routingCtx.headers())) {
                        return RoutingResult.empty();
                    }
                }
            }
        }

        // We assume that a user adds excluded routes as little as possible. It would be much better to split
        // routes if there's many routes to be excluded.
        if (!excludedRoutes.isEmpty()) {
            for (Route r : excludedRoutes) {
                if (r.apply(routingCtx, isRouteDecorator).isPresent()) {
                    return RoutingResult.excluded();
                }
            }
        }

        return builder.build();
    }

    private void deferStatusException(RoutingContext routingCtx, HttpStatus httpStatus) {
        if (isFallback) {
            // Do not defer an exception if this route is a fallback route, which is matched
            // only when no configured route was matched.
            //
            // For example, assume that a route '/a/b/c/' supports HTTP GET method only.
            // Its fallback route would be added as a path of '/a/b/c' with supporting HTTP GET method as well.
            // In this case, '404 not found' would make sense rather than '405 method not allowed'
            // if a 'DELETE /a/b/c' request is received, because the fallback route wasn't specified by a user.
            return;
        }
        routingCtx.deferStatusException(HttpStatusException.of(httpStatus));
    }

    private static RoutingResult emptyOrCorsPreflightResult(RoutingContext routingCtx,
                                                            RoutingResultBuilder builder) {
        if (routingCtx.status() == RoutingStatus.CORS_PREFLIGHT) {
            return builder.type(RoutingResultType.CORS_PREFLIGHT).build();
        }

        return RoutingResult.empty();
    }

    private static boolean isAnyType(MediaType contentType) {
        // Ignores all parameters including the quality factor.
        return "*".equals(contentType.type()) || "*".equals(contentType.subtype());
    }

    @Override
    public Set<String> paramNames() {
        return pathMapping.paramNames();
    }

    @Override
    public String patternString() {
        return pathMapping.patternString();
    }

    @Override
    public RoutePathType pathType() {
        return pathMapping.pathType();
    }

    @Override
    public List<String> paths() {
        return pathMapping.paths();
    }

    @Override
    public int complexity() {
        return complexity;
    }

    @Override
    public Set<HttpMethod> methods() {
        return methods;
    }

    @Override
    public Set<MediaType> consumes() {
        return consumes;
    }

    @Override
    public Set<MediaType> produces() {
        return produces;
    }

    @Override
    public boolean isFallback() {
        return isFallback;
    }

    @Override
    public List<Route> excludedRoutes() {
        return excludedRoutes;
    }

    @Override
    public RouteBuilder toBuilder() {
        return new RouteBuilder()
                .pathMapping(pathMapping)
                .methods(methods)
                .consumes(consumes)
                .produces(produces)
                .matchesParams(paramPredicates)
                .matchesHeaders(headerPredicates)
                .fallback(isFallback)
                .exclude(excludedRoutes);
    }

    @Override
    public Route withPrefix(String prefix) {
        requireNonNull(prefix, "prefix");
        if ("/".equals(prefix)) {
            return this;
        }
        return new DefaultRoute(pathMapping.withPrefix(prefix), methods, consumes, produces, paramPredicates,
                                headerPredicates, isFallback, excludedRoutes);
    }

    @Override
    public boolean isCacheable() {
        return paramPredicates.isEmpty() && headerPredicates.isEmpty() && excludedRoutes().isEmpty();
    }

    @Override
    public boolean hasConflicts(Route other) {
        if (complexity != other.complexity()) {
            return false;
        }

        if (getClass() != other.getClass()) {
            return false;
        }

        if (!pathType().hasTriePath() || !other.pathType().hasTriePath()) {
            return false;
        }

        if (!paths().get(1).equals(other.paths().get(1))) {
            // Just compare trie path.
            return false;
        }

        if (methods.stream().noneMatch(method -> other.methods().contains(method))) {
            // No overlap in supported methods.
            return false;
        }
        if (!consumes.isEmpty() &&
            consumes.stream().noneMatch(mediaType -> other.consumes().contains(mediaType))) {
            // No overlap in consume types.
            return false;
        }
        if (!produces.isEmpty() && produces.stream().noneMatch(
                mediaType -> other.produces().contains(mediaType))) {
            // No overlap in produce types.
            return false;
        }

        if (!paramPredicates.isEmpty() &&
            noneMatchAnyNameOfNonCustomPredicate(paramPredicates, ((DefaultRoute) other).paramPredicates)) {
            return false;
        }

        if (!headerPredicates.isEmpty() &&
            noneMatchAnyNameOfNonCustomPredicate(headerPredicates, ((DefaultRoute) other).headerPredicates)) {
            return false;
        }

        return true;
    }

    private static <T> boolean noneMatchAnyNameOfNonCustomPredicate(List<RoutingPredicate<T>> thisPredicates,
                                                                    List<RoutingPredicate<T>> otherPredicates) {
        final List<CharSequence> otherNames =
                otherPredicates.stream()
                               .filter(routingPredicate -> !routingPredicate.isCustomPredicate())
                               .map(RoutingPredicate::name)
                               .collect(toImmutableList());
        return thisPredicates.stream()
                             .filter(routingPredicate -> !routingPredicate.isCustomPredicate())
                             .map(RoutingPredicate::name)
                             .noneMatch(otherNames::contains);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof DefaultRoute)) {
            return false;
        }

        final DefaultRoute that = (DefaultRoute) o;
        return Objects.equals(pathMapping, that.pathMapping) &&
               methods.equals(that.methods) &&
               consumes.equals(that.consumes) &&
               produces.equals(that.produces) &&
               headerPredicates.equals(that.headerPredicates) &&
               paramPredicates.equals(that.paramPredicates) &&
               isFallback == that.isFallback &&
               excludedRoutes.equals(that.excludedRoutes);
    }

    @Override
    public String toString() {
        return patternString();
    }
}
