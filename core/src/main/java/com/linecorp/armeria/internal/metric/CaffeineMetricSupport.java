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

package com.linecorp.armeria.internal.metric;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.metric.MeterId;
import com.linecorp.armeria.common.util.Ticker;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers the stats of Caffeine {@link Cache}.
 */
public final class CaffeineMetricSupport {

    @VisibleForTesting
    static final long UPDATE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3);

    public static void setup(MeterRegistry registry, MeterId id, Cache<?, ?> cache) {
        setup(registry, id, cache, Ticker.systemTicker());
    }

    public static void setup(MeterRegistry registry, MeterId id, Cache<?, ?> cache, Ticker ticker) {
        final CaffeineMetrics metrics = MicrometerUtil.register(
                registry, id, CaffeineMetrics.class, (r, i) -> new CaffeineMetrics(r, i, cache, ticker));
        checkState(metrics.cache == cache, "Meters for a different Cache have been registered for id: %s", id);
    }

    private CaffeineMetricSupport() {}

    private static final class CaffeineMetrics {
        private final Cache<?, ?> cache;
        private final Ticker ticker;

        private volatile long lastStatsUpdateTime;
        private CacheStats cacheStats;
        private long estimatedSize;

        CaffeineMetrics(MeterRegistry parent, MeterId id, Cache<?, ?> cache, Ticker ticker) {
            requireNonNull(parent, "parent");
            this.cache = requireNonNull(cache, "cache");
            this.ticker = requireNonNull(ticker, "ticker");

            updateCacheStats(true);

            final String requests = id.name("requests");
            parent.more().counter(requests, id.tags("result", "hit"), this,
                                  new CacheStatFunction(() -> cacheStats.hitCount()));
            parent.more().counter(requests, id.tags("result", "miss"), this,
                                  new CacheStatFunction(() -> cacheStats.missCount()));

            if (cache instanceof LoadingCache) {
                final String loads = id.name("loads");
                parent.more().counter(loads, id.tags("result", "success"), this,
                                      new CacheStatFunction(() -> cacheStats.loadSuccessCount()));
                parent.more().counter(loads, id.tags("result", "failure"), this,
                                      new CacheStatFunction(() -> cacheStats.loadFailureCount()));

                final DoubleSupplier totalLoadTimeSupplier;
                totalLoadTimeSupplier = () -> cacheStats.totalLoadTime();
                parent.more().counter(id.name("loadDuration"), id.tags(), this,
                                      new CacheStatFunction(totalLoadTimeSupplier));
            }

            parent.more().counter(id.name("eviction"), id.tags(), this,
                                  new CacheStatFunction(() -> cacheStats.evictionCount()));
            parent.more().counter(id.name("evictionWeight"), id.tags(), this,
                                  new CacheStatFunction(() -> cacheStats.evictionWeight()));
            parent.gauge(id.name("estimatedSize"), id.tags(), this,
                         new CacheStatFunction(() -> estimatedSize));
        }

        private void updateCacheStats() {
            updateCacheStats(false);
        }

        private void updateCacheStats(boolean force) {
            final long currentTimeNanos = ticker.read();
            if (!force) {
                if (currentTimeNanos - lastStatsUpdateTime < UPDATE_INTERVAL_NANOS) {
                    return;
                }
            }

            cacheStats = cache.stats();
            estimatedSize = cache.estimatedSize();

            // Write the volatile field last so that cacheStats and estimatedSize are visible
            // after reading the volatile field.
            lastStatsUpdateTime = currentTimeNanos;
        }
    }

    private static final class CacheStatFunction implements ToDoubleFunction<CaffeineMetrics> {

        private final DoubleSupplier valueSupplier;

        CacheStatFunction(DoubleSupplier valueSupplier) {
            this.valueSupplier = valueSupplier;
        }

        @Override
        public double applyAsDouble(CaffeineMetrics value) {
            value.updateCacheStats();
            return valueSupplier.getAsDouble();
        }
    }
}
