/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.server.RouteCache.wrapRouteDecoratingServiceRouter;
import static com.linecorp.armeria.server.RouteCache.wrapVirtualHostRouter;
import static java.util.Objects.requireNonNull;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * A factory that creates a {@link Router} instance.
 */
public final class Routers {
    private static final Logger logger = LoggerFactory.getLogger(Routers.class);

    /**
     * Returns the default implementation of the {@link Router} to find a {@link ServiceConfig}.
     * It consists of several router implementations which use one of Trie and List. It also includes
     * cache mechanism to improve its performance.
     */
    public static Router<ServiceConfig> ofVirtualHost(VirtualHost virtualHost, Iterable<ServiceConfig> configs,
                                                      RejectedRouteHandler rejectionHandler) {
        requireNonNull(virtualHost, "virtualHost");
        requireNonNull(configs, "configs");
        requireNonNull(rejectionHandler, "rejectionHandler");

        final BiConsumer<Route, Route> rejectionConsumer = (route, existingRoute) -> {
            try {
                rejectionHandler.handleDuplicateRoute(virtualHost, route, existingRoute);
            } catch (Exception e) {
                logger.warn("Unexpected exception from a {}:",
                            RejectedRouteHandler.class.getSimpleName(), e);
            }
        };
        final Set<Route> ambiguousRoutes =
                resolveAmbiguousRoutes(StreamSupport.stream(configs.spliterator(), false)
                                                    .map(ServiceConfig::route)
                                                    .collect(toImmutableList()));
        return wrapVirtualHostRouter(defaultRouter(configs, virtualHost.fallbackServiceConfig(),
                                                   ServiceConfig::route, rejectionConsumer, false),
                                     ambiguousRoutes);
    }

    /**
     * Returns the default implementation of the {@link Router} to find a {@link RouteDecoratingService}.
     */
    public static Router<RouteDecoratingService> ofRouteDecoratingService(
            List<RouteDecoratingService> routeDecoratingServices) {
        return wrapRouteDecoratingServiceRouter(
                onlySequentialRouter(routeDecoratingServices, null, RouteDecoratingService::route,
                                     (route1, route2) -> {/* noop */}, true),
                resolveAmbiguousRoutes(routeDecoratingServices.stream()
                                                              .map(RouteDecoratingService::route)
                                                              .collect(toImmutableList())));
    }

    /**
     * Finds the {@link Route}s that are not unique based on the following properties.
     * <ul>
     *     <li>{@link Route#pathType()}</li>
     *     <li>{@link Route#paths()}</li>
     *     <li>{@link Route#methods()}</li>
     *     <li>{@link Route#consumes()}</li>
     *     <li>{@link Route#produces()}</li>
     * </ul>
     */
    private static Set<Route> resolveAmbiguousRoutes(List<Route> allRoutes) {
        final Map<List<Object>, List<Route>> dup = new HashMap<>();
        allRoutes.forEach(route -> {
            final List<Object> key = ImmutableList.builder()
                                                  .add(route.pathType())
                                                  .addAll(route.paths())
                                                  .addAll(route.methods())
                                                  .addAll(route.consumes())
                                                  .addAll(route.produces())
                                                  .build();
            dup.computeIfAbsent(key, unused -> new ArrayList<>())
               .add(route);
        });
        return dup.values().stream()
                  .filter(routes -> routes.size() > 1)  // ambiguous routes
                  .flatMap(Collection::stream).collect(toImmutableSet());
    }

    /**
     * Returns the default implementation of {@link Router}. It consists of several router implementations
     * which use one of Trie and List. Consecutive {@link ServiceConfig}s would be grouped according to whether
     * it is able to produce trie path string or not while traversing the list, then each group would be
     * transformed to a {@link Router}.
     */
    private static <V> Router<V> defaultRouter(Iterable<V> values, @Nullable V fallbackValue,
                                               Function<V, Route> routeResolver,
                                               BiConsumer<Route, Route> rejectionHandler,
                                               boolean isRouteDecorator) {
        return new CompositeRouter<>(routers(values, fallbackValue, routeResolver, rejectionHandler,
                                             isRouteDecorator),
                                     Function.identity());
    }

    static <V> Router<V> onlySequentialRouter(Iterable<V> values, @Nullable V fallbackValue,
                                              Function<V, Route> routeResolver,
                                              BiConsumer<Route, Route> rejectionHandler,
                                              boolean isRouteDecorator) {
        rejectDuplicateMapping(values, routeResolver, rejectionHandler);
        return router(false, Lists.newArrayList(values), fallbackValue, routeResolver,
                      isRouteDecorator);
    }

    /**
     * Returns a list of {@link Router}s.
     */
    @VisibleForTesting
    static <V> List<Router<V>> routers(Iterable<V> values, @Nullable V fallbackValue,
                                       Function<V, Route> routeResolver,
                                       BiConsumer<Route, Route> rejectionHandler,
                                       boolean isRouteDecorator) {
        rejectDuplicateMapping(values, routeResolver, rejectionHandler);

        final ImmutableList.Builder<Router<V>> builder = ImmutableList.builder();
        final List<V> group = new ArrayList<>();

        boolean addingTrie = true;

        for (V value : values) {
            final Route route = routeResolver.apply(value);
            final boolean hasTriePath = route.pathType().hasTriePath();
            if (addingTrie && hasTriePath || !addingTrie && !hasTriePath) {
                // We are adding the same type of Route to 'group'.
                group.add(value);
                continue;
            }

            // Changed the router type.
            if (!group.isEmpty()) {
                builder.add(router(addingTrie, group, fallbackValue, routeResolver, isRouteDecorator));
            }
            addingTrie = !addingTrie;
            group.add(value);
        }
        if (!group.isEmpty()) {
            builder.add(router(addingTrie, group, fallbackValue, routeResolver, isRouteDecorator));
        }
        return builder.build();
    }

    private static <V> void rejectDuplicateMapping(
            Iterable<V> values, Function<V, Route> routeResolver,
            BiConsumer<Route, Route> rejectionHandler) {

        final Map<String, List<Route>> triePath2Routes = new HashMap<>();
        for (V v : values) {
            final Route route = routeResolver.apply(v);
            final boolean hasTriePath = route.pathType().hasTriePath();
            if (!hasTriePath) {
                continue;
            }
            final String triePath = route.paths().get(1);
            final List<Route> existingRoutes =
                    triePath2Routes.computeIfAbsent(triePath, unused -> new ArrayList<>());
            for (Route existingRoute : existingRoutes) {
                if (route.complexity() != existingRoute.complexity()) {
                    continue;
                }

                if (route.getClass() != existingRoute.getClass()) {
                    continue;
                }

                if (route.methods().stream().noneMatch(
                        method -> existingRoute.methods().contains(method))) {
                    // No overlap in supported methods.
                    continue;
                }
                if (!route.consumes().isEmpty() &&
                    route.consumes().stream().noneMatch(
                            mediaType -> existingRoute.consumes().contains(mediaType))) {
                    // No overlap in consume types.
                    continue;
                }
                if (!route.produces().isEmpty() &&
                    route.produces().stream().noneMatch(
                            mediaType -> existingRoute.produces().contains(mediaType))) {
                    // No overlap in produce types.
                    continue;
                }

                rejectionHandler.accept(route, existingRoute);
                return;
            }

            existingRoutes.add(route);
        }
    }

    /**
     * Returns a {@link Router} implementation which is using one of {@link RoutingTrie} and {@link List}.
     */
    private static <V> Router<V> router(boolean isTrie, List<V> values, @Nullable V fallbackValue,
                                        Function<V, Route> routeResolver, boolean isRouteDecorator) {
        final Comparator<V> valueComparator =
                Comparator.comparingInt(e -> -1 * routeResolver.apply(e).complexity());

        final Router<V> router;
        if (isTrie) {
            final RoutingTrieBuilder<V> builder = new RoutingTrieBuilder<>();
            // Set a comparator to sort services by the number of conditions to check in a descending order.
            builder.comparator(valueComparator);
            for (V v : values) {
                final Route route = routeResolver.apply(v);
                builder.add(route.paths().get(1), v);

                if (fallbackValue != null) {
                    // Add an extra route without a trailing slash for a redirect.
                    // Note that `path.length()` must be greater than 1 because path is `/` when 1.
                    final String path = route.paths().get(0);
                    final int pathLen = path.length();
                    if (pathLen > 1 && path.charAt(pathLen - 1) == '/') {
                        builder.add(path.substring(0, pathLen - 1),
                                    fallbackValue, /* hasHighPrecedence */ false);
                    }
                }
            }
            router = new TrieRouter<>(builder.build(), routeResolver, isRouteDecorator);
        } else {
            values.sort(valueComparator);
            router = new SequentialRouter<>(values, routeResolver, isRouteDecorator);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Router created for {} service(s): {}",
                         values.size(), router.getClass().getSimpleName());
            for (V v : values) {
                final Route route = routeResolver.apply(v);
                logger.debug("patternString: {}, complexity: {}", route.patternString(), route.complexity());
            }
        }
        values.clear();
        return router;
    }

    /**
     * Finds the most suitable service from the given {@link ServiceConfig} list.
     */
    private static <V> Routed<V> findBest(RoutingContext routingCtx, @Nullable List<V> values,
                                          Function<V, Route> routeResolver) {
        Routed<V> result = Routed.empty();
        if (values != null) {
            for (V value : values) {
                final Route route = routeResolver.apply(value);
                final RoutingResult routingResult = route.apply(routingCtx, false);
                if (routingResult.isPresent()) {
                    //
                    // The services are sorted as follows:
                    //
                    // 1) the service with method and media type negotiation
                    //    (consumable and producible)
                    // 2) the service with method and producible media type negotiation
                    // 3) the service with method and consumable media type negotiation
                    // 4) the service with method negotiation
                    // 5) the other services (in a registered order)
                    //
                    // 1) and 2) may produce a score between the lowest and the highest because they should
                    // negotiate the produce type with the value of 'Accept' header.
                    // 3), 4) and 5) always produces the lowest score.
                    //

                    // Found the best matching.
                    if (routingResult.hasHighestScore()) {
                        result = Routed.of(route, routingResult, value);
                        break;
                    }

                    // We have still a chance to find a better matching.
                    if (result.isPresent()) {
                        if (routingResult.score() > result.routingResult().score()) {
                            // Replace the candidate with the new one only if the score is better.
                            // If the score is same, we respect the order of service registration.
                            result = Routed.of(route, routingResult, value);
                        }
                    } else {
                        // Keep the result as a candidate.
                        result = Routed.of(route, routingResult, value);
                    }
                }
            }
        }
        return result;
    }

    private static <V> List<Routed<V>> findAll(RoutingContext routingCtx, List<V> values,
                                               Function<V, Route> routeResolver,
                                               boolean isRouteDecorator) {
        final ImmutableList.Builder<Routed<V>> builder = ImmutableList.builderWithExpectedSize(values.size());

        for (V value : values) {
            final Route route = routeResolver.apply(value);
            final RoutingResult routingResult = route.apply(routingCtx, isRouteDecorator);
            if (routingResult.isPresent()) {
                builder.add(Routed.of(route, routingResult, value));
            }
        }
        return builder.build();
    }

    private static final class TrieRouter<V> implements Router<V> {

        private final RoutingTrie<V> trie;
        private final Function<V, Route> routeResolver;
        private final boolean isRouteDecorator;

        TrieRouter(RoutingTrie<V> trie, Function<V, Route> routeResolver, boolean isRouteDecorator) {
            this.trie = requireNonNull(trie, "trie");
            this.routeResolver = requireNonNull(routeResolver, "routeResolver");
            this.isRouteDecorator = isRouteDecorator;
        }

        @Override
        public Routed<V> find(RoutingContext routingCtx) {
            return findBest(routingCtx, trie.find(routingCtx.path()), routeResolver);
        }

        @Override
        public List<Routed<V>> findAll(RoutingContext routingCtx) {
            return Routers.findAll(routingCtx, trie.findAll(routingCtx.path()),
                                   routeResolver, isRouteDecorator);
        }

        @Override
        public void dump(OutputStream output) {
            trie.dump(output);
        }
    }

    private static final class SequentialRouter<V> implements Router<V> {

        private final List<V> values;
        private final Function<V, Route> routeResolver;
        private final boolean isRouteDecorator;

        SequentialRouter(List<V> values, Function<V, Route> routeResolver, boolean isRouteDecorator) {
            this.values = ImmutableList.copyOf(requireNonNull(values, "values"));
            this.routeResolver = requireNonNull(routeResolver, "routeResolver");
            this.isRouteDecorator = isRouteDecorator;
        }

        @Override
        public Routed<V> find(RoutingContext routingCtx) {
            return findBest(routingCtx, values, routeResolver);
        }

        @Override
        public List<Routed<V>> findAll(RoutingContext routingCtx) {
            return Routers.findAll(routingCtx, values, routeResolver, isRouteDecorator);
        }

        @Override
        public void dump(OutputStream output) {
            // Do not close this writer in order to keep output stream open.
            final PrintWriter p = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            p.printf("Dump of %s:%n", this);
            for (int i = 0; i < values.size(); i++) {
                p.printf("<%d> %s%n", i, values.get(i));
            }
            p.flush();
        }
    }

    private Routers() {}
}
