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
package com.linecorp.armeria.internal.common;

import java.util.Set;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport;

import io.micrometer.core.instrument.MeterRegistry;

public final class RequestTargetCache {

    private static final MeterIdPrefix METER_ID_PREFIX = new MeterIdPrefix("armeria.path.cache");

    @Nullable
    private static final Cache<String, RequestTarget> SERVER_CACHE =
            Flags.parsedPathCacheSpec() != null ? buildCache(Flags.parsedPathCacheSpec()) : null;

    @Nullable
    private static final Cache<String, RequestTarget> CLIENT_CACHE =
            Flags.parsedPathCacheSpec() != null ? buildCache(Flags.parsedPathCacheSpec()) : null;

    private static Cache<String, RequestTarget> buildCache(String spec) {
        return Caffeine.from(spec).build();
    }

    public static void registerServerMetrics(MeterRegistry registry) {
        if (SERVER_CACHE != null) {
            CaffeineMetricSupport.setup(registry, METER_ID_PREFIX.withTags("type", "server"), SERVER_CACHE);
        }
    }

    public static void registerClientMetrics(MeterRegistry registry) {
        if (CLIENT_CACHE != null) {
            CaffeineMetricSupport.setup(registry, METER_ID_PREFIX.withTags("type", "client"), CLIENT_CACHE);
        }
    }

    @Nullable
    public static RequestTarget getForServer(String reqTarget) {
        return get(reqTarget, SERVER_CACHE);
    }

    @Nullable
    public static RequestTarget getForClient(String reqTarget) {
        return get(reqTarget, CLIENT_CACHE);
    }

    @Nullable
    private static RequestTarget get(String reqTarget, @Nullable Cache<String, RequestTarget> cache) {
        if (cache != null) {
            return cache.getIfPresent(reqTarget);
        } else {
            return null;
        }
    }

    public static void putForServer(String reqTarget, RequestTarget normalized) {
        put(reqTarget, normalized, SERVER_CACHE);
    }

    public static void putForClient(String reqTarget, RequestTarget normalized) {
        put(reqTarget, normalized, CLIENT_CACHE);
    }

    private static void put(String reqTarget, RequestTarget normalized,
                            @Nullable Cache<String, RequestTarget> cache) {
        assert reqTarget != null;
        assert normalized != null;

        if (cache != null && normalized instanceof DefaultRequestTarget) {
            final DefaultRequestTarget value = (DefaultRequestTarget) normalized;
            if (!value.isCached()) {
                value.setCached();
                cache.put(reqTarget, normalized);
            }
        }
    }

    /**
     * Clears the currently cached parsed paths. Only for use in tests.
     */
    @VisibleForTesting
    public static void clearCachedPaths() {
        assert CLIENT_CACHE != null : "CLIENT_CACHE";
        assert SERVER_CACHE != null : "SERVER_CACHE";
        CLIENT_CACHE.asMap().clear();
        SERVER_CACHE.asMap().clear();
    }

    /**
     * Returns server-side paths that have had their parse result cached. Only for use in tests.
     */
    @VisibleForTesting
    public static Set<String> cachedServerPaths() {
        assert SERVER_CACHE != null : "SERVER_CACHE";
        return SERVER_CACHE.asMap().keySet();
    }

    /**
     * Returns client-side paths that have had their parse result cached. Only for use in tests.
     */
    @VisibleForTesting
    public static Set<String> cachedClientPaths() {
        assert CLIENT_CACHE != null : "CLIENT_CACHE";
        return CLIENT_CACHE.asMap().keySet();
    }

    private RequestTargetCache() {}
}
