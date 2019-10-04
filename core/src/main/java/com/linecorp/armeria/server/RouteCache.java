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
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.metric.CaffeineMetricSupport;
import com.linecorp.armeria.server.composition.CompositeServiceEntry;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * See {@link Flags#routeCacheSpec()} to configure this {@link RouteCache}.
 */
final class RouteCache {

    @Nullable
    private static final Cache<RoutingContext, ServiceConfig> FIND_CACHE =
            Flags.routeCacheSpec().map(RouteCache::<ServiceConfig>buildCache)
                 .orElse(null);

    @Nullable
    private static final Cache<RoutingContext, List<ServiceConfig>> FIND_ALL_CACHE =
            Flags.routeCacheSpec().map(RouteCache::<List<ServiceConfig>>buildCache)
                 .orElse(null);

    @Nullable
    private static final Cache<RoutingContext, RouteDecoratingService> DECORATOR_FIND_CACHE =
            Flags.routeDecoratorCacheSpec().map(RouteCache::<RouteDecoratingService>buildCache)
                 .orElse(null);

    @Nullable
    private static final Cache<RoutingContext, List<RouteDecoratingService>> DECORATOR_FIND_ALL_CACHE =
            Flags.routeDecoratorCacheSpec().map(RouteCache::<List<RouteDecoratingService>>buildCache)
                 .orElse(null);

    /**
     * Returns a {@link Router} which is wrapped with a {@link Cache} layer in order to improve the
     * performance of the {@link ServiceConfig} search.
     */
    static Router<ServiceConfig> wrapVirtualHostRouter(Router<ServiceConfig> delegate) {
        return FIND_CACHE == null ? delegate
                                  : new CachingRouter<>(delegate, ServiceConfig::route,
                                                        FIND_CACHE, FIND_ALL_CACHE);
    }

    /**
     * Returns a {@link Router} which is wrapped with a {@link Cache} layer in order to improve the
     * performance of the {@link CompositeServiceEntry} search.
     */
    static <I extends Request, O extends Response>
    Router<CompositeServiceEntry<I, O>> wrapCompositeServiceRouter(
            Router<CompositeServiceEntry<I, O>> delegate) {

        final Cache<RoutingContext, CompositeServiceEntry<I, O>> cache =
                Flags.compositeServiceCacheSpec().map(RouteCache::<CompositeServiceEntry<I, O>>buildCache)
                     .orElse(null);
        if (cache == null) {
            return delegate;
        }

        final Cache<RoutingContext, List<CompositeServiceEntry<I, O>>> listCache =
                Flags.compositeServiceCacheSpec().map(RouteCache::<List<CompositeServiceEntry<I, O>>>buildCache)
                     .orElse(null);

        return new CachingRouter<>(delegate, CompositeServiceEntry::route, cache, listCache);
    }

    /**
     * Returns a {@link Router} which is wrapped with a {@link Cache} layer in order to improve the
     * performance of the {@link RouteDecoratingService} search.
     */
    static Router<RouteDecoratingService> wrapRouteDecoratingServiceRouter(
            Router<RouteDecoratingService> delegate) {
        return DECORATOR_FIND_CACHE == null ? delegate
                                            : new CachingRouter<>(delegate, RouteDecoratingService::route,
                                                                  DECORATOR_FIND_CACHE,
                                                                  DECORATOR_FIND_ALL_CACHE);
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

        CachingRouter(Router<V> delegate, Function<V, Route> routeResolver,
                      Cache<RoutingContext, V> findCache,
                      Cache<RoutingContext, List<V>> findAllCache) {
            this.delegate = requireNonNull(delegate, "delegate");
            this.routeResolver = requireNonNull(routeResolver, "routeResolver");
            this.findCache = requireNonNull(findCache, "findCache");
            this.findAllCache = requireNonNull(findAllCache, "findAllCache");
        }

        @Override
        public Routed<V> find(RoutingContext routingCtx) {
            final V cached = findCache.getIfPresent(routingCtx);
            if (cached != null) {
                // RoutingResult may be different to each other for every requests, so we cannot
                // use it as a cache value.
                final Route route = routeResolver.apply(cached);
                final RoutingResult routingResult = route.apply(routingCtx);
                return Routed.of(route, routingResult, cached);
            }

            final Routed<V> result = delegate.find(routingCtx);
            if (result.isPresent()) {
                findCache.put(routingCtx, result.value());
            }
            return result;
        }

        @Override
        public Stream<Routed<V>> findAll(RoutingContext routingCtx) {
            final List<V> cachedList = findAllCache.getIfPresent(routingCtx);
            if (cachedList != null) {
                return cachedList.stream().map(cached -> {
                    final Route route = routeResolver.apply(cached);
                    final RoutingResult routingResult = route.apply(routingCtx);
                    return Routed.of(route, routingResult, cached);
                });
            }

            final List<Routed<V>> result = delegate.findAll(routingCtx).collect(toImmutableList());
            final List<V> valid = result.stream()
                                        .filter(Routed::isPresent)
                                        .map(Routed::value)
                                        .collect(toImmutableList());
            findAllCache.put(routingCtx, valid);
            return result.stream();
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
}
