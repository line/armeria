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

import static java.util.Objects.requireNonNull;

import java.io.OutputStream;
import java.util.function.Function;

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

    private static final Cache<PathMappingContext, ServiceConfig> CACHE =
            Flags.routeCacheSpec().map(RouteCache::<ServiceConfig>buildCache)
                 .orElse(null);

    /**
     * Returns a {@link Router} which is wrapped with a {@link Cache} layer in order to improve the
     * performance of the {@link ServiceConfig} search.
     */
    static Router<ServiceConfig> wrapVirtualHostRouter(Router<ServiceConfig> delegate) {
        return CACHE == null ? delegate
                             : new CachingRouter<>(delegate, CACHE, ServiceConfig::pathMapping);
    }

    /**
     * Returns a {@link Router} which is wrapped with a {@link Cache} layer in order to improve the
     * performance of the {@link CompositeServiceEntry} search.
     */
    static <I extends Request, O extends Response>
    Router<CompositeServiceEntry<I, O>> wrapCompositeServiceRouter(
            Router<CompositeServiceEntry<I, O>> delegate) {

        final Cache<PathMappingContext, CompositeServiceEntry<I, O>> cache =
                Flags.compositeServiceCacheSpec().map(RouteCache::<CompositeServiceEntry<I, O>>buildCache)
                     .orElse(null);
        if (cache == null) {
            return delegate;
        }

        return new CachingRouter<>(delegate, cache, CompositeServiceEntry::pathMapping);
    }

    private static <T> Cache<PathMappingContext, T> buildCache(String spec) {
        return Caffeine.from(spec).recordStats().build();
    }

    private RouteCache() {}

    /**
     * A {@link Router} which is wrapped with a {@link Cache} layer.
     */
    private static final class CachingRouter<V> implements Router<V> {

        private final Router<V> delegate;
        private final Cache<PathMappingContext, V> cache;
        private final Function<V, PathMapping> pathMappingResolver;

        CachingRouter(Router<V> delegate, Cache<PathMappingContext, V> cache,
                      Function<V, PathMapping> pathMappingResolver) {
            this.delegate = requireNonNull(delegate, "delegate");
            this.cache = requireNonNull(cache, "cache");
            this.pathMappingResolver = requireNonNull(pathMappingResolver, "pathMappingResolver");
        }

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

            final PathMapped<V> result = delegate.find(mappingCtx);
            if (result.isPresent()) {
                cache.put(mappingCtx, result.value());
            }
            return result;
        }

        @Override
        public boolean registerMetrics(MeterRegistry registry, MeterIdPrefix idPrefix) {
            CaffeineMetricSupport.setup(registry, idPrefix, cache);
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
                              .add("cache", cache)
                              .toString();
        }
    }
}
