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
import static java.util.Objects.requireNonNull;

import java.io.OutputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport;
import com.linecorp.armeria.internal.server.RouteDecoratingService;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * See {@link Flags#routeCacheSpec()} to configure this {@link RouteCache}.
 */
final class RouteCache {

    @Nullable
    private static final Cache<RoutingContext, ServiceConfig> FIND_CACHE =
            Flags.routeCacheSpec() != null ? buildCache(Flags.routeCacheSpec()) : null;

    @Nullable
    private static final Cache<RoutingContext, List<ServiceConfig>> FIND_ALL_CACHE =
            Flags.routeCacheSpec() != null ? buildCache(Flags.routeCacheSpec()) : null;

    @Nullable
    private static final Cache<RoutingContext, RouteDecoratingService> DECORATOR_FIND_CACHE =
            Flags.routeDecoratorCacheSpec() != null ? buildCache(Flags.routeDecoratorCacheSpec()) : null;

    @Nullable
    private static final Cache<RoutingContext, List<RouteDecoratingService>> DECORATOR_FIND_ALL_CACHE =
            Flags.routeDecoratorCacheSpec() != null ? buildCache(Flags.routeDecoratorCacheSpec()) : null;

    /**
     * Returns a {@link Router} which is wrapped with a {@link Cache} layer in order to improve the
     * performance of the {@link ServiceConfig} search.
     */
    static Router<ServiceConfig> wrapVirtualHostRouter(Router<ServiceConfig> delegate,
                                                       Set<Route> dynamicPredicateRoutes) {
        return FIND_CACHE == null ? delegate
                                  : new CachingRouter<>(delegate, ServiceConfig::route,
                                                        FIND_CACHE, FIND_ALL_CACHE, dynamicPredicateRoutes);
    }

    /**
     * Returns a {@link Router} which is wrapped with a {@link Cache} layer in order to improve the
     * performance of the {@link RouteDecoratingService} search.
     */
    static Router<RouteDecoratingService> wrapRouteDecoratingServiceRouter(
            Router<RouteDecoratingService> delegate, Set<Route> dynamicPredicateRoutes) {
        return DECORATOR_FIND_CACHE == null ? delegate
                                            : new CachingRouter<>(delegate, RouteDecoratingService::route,
                                                                  DECORATOR_FIND_CACHE,
                                                                  DECORATOR_FIND_ALL_CACHE,
                                                                  dynamicPredicateRoutes);
    }

    private static <T> Cache<RoutingContext, T> buildCache(String spec) {
        return Caffeine.from(spec).recordStats().build();
    }

    private RouteCache() {}

    /**
     * A {@link Router} which is wrapped with a {@link Cache} layer.
     */
    private static final class CachingRouter<V> implements Router<V> {

        private final Router<V> delegate;
        private final Function<V, Route> routeResolver;
        private final Cache<RoutingContext, V> findCache;
        private final Cache<RoutingContext, List<V>> findAllCache;
        private final Set<Route> dynamicPredicateRoutes;

        CachingRouter(Router<V> delegate, Function<V, Route> routeResolver,
                      Cache<RoutingContext, V> findCache,
                      Cache<RoutingContext, List<V>> findAllCache,
                      Set<Route> dynamicPredicateRoutes) {
            this.delegate = requireNonNull(delegate, "delegate");
            this.routeResolver = requireNonNull(routeResolver, "routeResolver");
            this.findCache = requireNonNull(findCache, "findCache");
            this.findAllCache = requireNonNull(findAllCache, "findAllCache");

            final Set<Route> newDynamicPredicateRoutes =
                    Collections.newSetFromMap(new IdentityHashMap<>(dynamicPredicateRoutes.size()));
            newDynamicPredicateRoutes.addAll(requireNonNull(dynamicPredicateRoutes, "dynamicPredicateRoutes"));
            this.dynamicPredicateRoutes = Collections.unmodifiableSet(newDynamicPredicateRoutes);
        }

        @Override
        public Routed<V> find(RoutingContext routingCtx) {
            final V cached = findCache.getIfPresent(routingCtx);
            if (cached != null) {
                // RoutingResult may be different to each other for every requests, so we cannot
                // use it as a cache value.
                final Route route = routeResolver.apply(cached);
                final RoutingResult routingResult = route.apply(routingCtx, false);
                return Routed.of(route, routingResult, cached);
            }

            final Routed<V> result = delegate.find(routingCtx);
            if (result.isPresent() && result.route().isCacheable() &&
                !dynamicPredicateRoutes.contains(result.route())) {
                findCache.put(routingCtx, result.value());
            }
            return result;
        }

        @Override
        public List<Routed<V>> findAll(RoutingContext routingCtx) {
            final List<V> cachedList = findAllCache.getIfPresent(routingCtx);
            if (cachedList != null) {
                return filterRoutes(cachedList, routingCtx);
            }

            // Disable matching headers and/or query parameters only if there's dynamic predicate routes.
            final List<Routed<V>> result = delegate.findAll(
                    dynamicPredicateRoutes.isEmpty() ? routingCtx : new CachingRoutingContext(routingCtx));
            final List<V> valid = result.stream()
                                        .filter(Routed::isPresent)
                                        .map(Routed::value)
                                        .collect(toImmutableList());
            findAllCache.put(routingCtx, valid);
            return filterRoutes(valid, routingCtx);
        }

        private List<Routed<V>> filterRoutes(List<V> list, RoutingContext routingCtx) {
            return list.stream().map(cached -> {
                final Route route = routeResolver.apply(cached);
                final RoutingResult routingResult = route.apply(routingCtx, false);
                return routingResult.isPresent() ? Routed.of(route, routingResult, cached)
                                                 : Routed.<V>empty();
            }).filter(Routed::isPresent).collect(toImmutableList());
        }

        @Override
        public boolean registerMetrics(MeterRegistry registry, MeterIdPrefix idPrefix) {
            CaffeineMetricSupport.setup(registry, idPrefix, findCache);
            return true;
        }

        @Override
        public void dump(OutputStream output) {
            delegate.dump(output);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("delegate", delegate)
                              .add("findCache", findCache)
                              .add("findAllCache", findAllCache)
                              .toString();
        }
    }

    @VisibleForTesting
    static final class CachingRoutingContext extends RoutingContextWrapper {

        CachingRoutingContext(RoutingContext delegate) {
            super(delegate);
        }

        @Override
        public boolean requiresMatchingParamsPredicates() {
            return false;
        }

        @Override
        public boolean requiresMatchingHeadersPredicates() {
            return false;
        }
    }
}
