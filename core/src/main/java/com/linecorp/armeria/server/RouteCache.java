/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.composition.CompositeServiceEntry;

import io.prometheus.client.cache.caffeine.CacheMetricsCollector;

/**
 * See {@link Flags#routeCacheSpec()} to configure this {@link RouteCache}.
 */
final class RouteCache {

    // TODO(hyangtack) Remove this after trustin's metric implementation PR is merged.
    static final CacheMetricsCollector CACHE_METRICS_COLLECTOR;

    static final Cache<PathMappingContext, ServiceConfig> CACHE;

    static {
        final Cache<PathMappingContext, ServiceConfig> cache =
                Flags.routeCacheSpec().map(
                        spec -> Caffeine.from(spec).recordStats()
                                        .<PathMappingContext, ServiceConfig>build())
                     .orElse(null);
        if (cache != null) {
            CACHE_METRICS_COLLECTOR = new CacheMetricsCollector().register();
            CACHE_METRICS_COLLECTOR.addCache(Router.class.getClass().getSimpleName(), cache);
        } else {
            CACHE_METRICS_COLLECTOR = null;
        }

        CACHE = cache;
    }

    /**
     * Returns a {@link Router} which is wrapped with a {@link Cache} layer in order to improve the
     * performance of the {@link ServiceConfig} search.
     */
    static Router<ServiceConfig> wrap(Router<ServiceConfig> delegate) {
        return CACHE == null ? delegate
                             : wrap(CACHE, delegate, ServiceConfig::pathMapping);
    }

    /**
     * Returns a {@link Router} which is wrapped with a {@link Cache} layer in order to improve the
     * performance of the {@link CompositeServiceEntry} search.
     */
    static <I extends Request, O extends Response> Router<CompositeServiceEntry<I, O>> wrap(
            String cacheName, Router<CompositeServiceEntry<I, O>> delegate) {
        requireNonNull(cacheName, "cacheName");

        final Cache<PathMappingContext, CompositeServiceEntry<I, O>> cache =
                Flags.compositeServiceCacheSpec().map(
                        spec -> Caffeine.from(spec).recordStats()
                                        .<PathMappingContext, CompositeServiceEntry<I, O>>build())
                     .orElse(null);
        if (cache == null) {
            return delegate;
        }

        CACHE_METRICS_COLLECTOR.addCache(cacheName, cache);
        return wrap(cache, delegate, CompositeServiceEntry::pathMapping);
    }

    /**
     * Returns a {@link Router} which is wrapped with a {@link Cache} layer.
     */
    private static <V> Router<V> wrap(Cache<PathMappingContext, V> cache, Router<V> delegate,
                                      Function<V, PathMapping> pathMappingResolver) {
        return new CompositeRouter<V, V>(delegate, Function.identity()) {
            @Override
            public PathMapped<V> find(PathMappingContext mappingCtx) {
                final V cached = cache.getIfPresent(mappingCtx);
                if (cached != null) {
                    // PathMappingResult may be different to each other for every requests, so we cannot
                    // use it as a cache value.
                    final PathMapping mapping = pathMappingResolver.apply(cached);
                    final PathMappingResult mappingResult = mapping.apply(mappingCtx);
                    return PathMapped.of(mapping, mappingResult, cached);
                }

                final PathMapped<V> result = super.find(mappingCtx);
                if (result.isPresent()) {
                    cache.put(mappingCtx, result.value());
                }
                return result;
            }
        };
    }

    private RouteCache() {}
}
