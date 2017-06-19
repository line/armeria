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

import static com.linecorp.armeria.common.metric.MetricUnit.COUNT;
import static com.linecorp.armeria.common.metric.MetricUnit.COUNT_CUMULATIVE;
import static com.linecorp.armeria.common.metric.MetricUnit.NANOSECONDS_CUMULATIVE;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.metric.CacheMetrics;
import com.linecorp.armeria.common.metric.Gauge;
import com.linecorp.armeria.common.metric.MetricKey;
import com.linecorp.armeria.common.metric.MetricUnit;
import com.linecorp.armeria.common.metric.Metrics;
import com.linecorp.armeria.common.metric.SupplierGauge;
import com.linecorp.armeria.common.util.Ticker;

/**
 * {@link CacheMetrics} implementation that provides the stats of Caffeine {@link Cache}.
 */
public final class CaffeineMetrics implements CacheMetrics {

    @VisibleForTesting
    static final long UPDATE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final MetricKey key;
    private final Cache<?, ?> cache;
    private final Ticker ticker;

    private volatile long lastStatsUpdateTime;
    private CacheStats cacheStats;
    private long estimatedSize;

    private final Gauge total;
    private final Gauge hit;
    private final Gauge miss;
    private final Gauge loadTotal;
    private final Gauge loadSuccess;
    private final Gauge loadFailure;
    private final Gauge loadDuration;
    private final Gauge eviction;
    private final Gauge evictionWeight;
    private final Gauge estimatedSizeGauge;

    /**
     * Creates a new instance with the specified {@link Metrics}, {@link MetricKey} and {@link Cache}.
     */
    public CaffeineMetrics(Metrics parent, MetricKey key, Cache<?, ?> cache) {
        this(parent, key, cache, Ticker.systemTicker());
    }

    /**
     * Creates a new instance with the specified {@link Metrics}, {@link MetricKey} and {@link Cache}.
     */
    public CaffeineMetrics(Metrics parent, MetricKey key, Cache<?, ?> cache, Ticker ticker) {
        this.key = requireNonNull(key, "key");
        this.cache = requireNonNull(cache, "cache");
        this.ticker = requireNonNull(ticker, "ticker");

        updateCacheStats(true);

        hit = parent.register(new CacheStatGauge(
                key.append("hit"), COUNT_CUMULATIVE, "the number of cache hits",
                () -> cacheStats.hitCount()));
        miss = parent.register(new CacheStatGauge(
                key.append("miss"), COUNT_CUMULATIVE, "the number of cache misses",
                () -> cacheStats.missCount()));

        total = parent.register(new CacheStatGauge(
                key.append("total"), COUNT_CUMULATIVE, "the number of cache hits and misses",
                () -> cacheStats.requestCount()));

        loadSuccess = parent.register(new CacheStatGauge(
                key.append("loadSuccess"), COUNT_CUMULATIVE, "the number of successful cache loads",
                () -> cacheStats.loadSuccessCount()));
        loadFailure = parent.register(new CacheStatGauge(
                key.append("loadFailure"), COUNT_CUMULATIVE, "the number of failed cache loads",
                () -> cacheStats.loadFailureCount()));
        loadTotal = parent.register(new CacheStatGauge(
                key.append("loadTotal"), COUNT_CUMULATIVE, "the number of cache loads (success and failure)",
                () -> cacheStats.loadCount()));

        loadDuration = parent.register(new CacheStatGauge(
                key.append("loadDuration"), NANOSECONDS_CUMULATIVE, "the total load time (success and failure)",
                () -> cacheStats.totalLoadTime()));

        eviction = parent.register(new CacheStatGauge(
                key.append("eviction"), COUNT_CUMULATIVE, "the number of evicted entries",
                () -> cacheStats.evictionCount()));
        evictionWeight = parent.register(new CacheStatGauge(
                key.append("evictionWeight"), COUNT_CUMULATIVE, "the sum of weight of evicted entries",
                () -> cacheStats.evictionWeight()));

        estimatedSizeGauge = parent.register(new CacheStatGauge(
                key.append("estimatedSize"), COUNT, "the approximate number of entries",
                () -> estimatedSize));
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

        // Write the volatile field lastly so that cacheStats and estimatedSize are visible
        // after reading the volatile field.
        lastStatsUpdateTime = currentTimeNanos;
    }

    @Override
    public MetricKey key() {
        return key;
    }

    @Override
    public Gauge total() {
        return total;
    }

    @Override
    public Gauge hit() {
        return hit;
    }

    @Override
    public Gauge miss() {
        return miss;
    }

    @Override
    public Gauge loadTotal() {
        return loadTotal;
    }

    @Override
    public Gauge loadSuccess() {
        return loadSuccess;
    }

    @Override
    public Gauge loadFailure() {
        return loadFailure;
    }

    @Override
    public Gauge loadDuration() {
        return loadDuration;
    }

    @Override
    public Gauge eviction() {
        return eviction;
    }

    @Override
    public Gauge evictionWeight() {
        return evictionWeight;
    }

    @Override
    public Gauge estimatedSize() {
        return estimatedSizeGauge;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("key", key)
                          .add("total", total.value())
                          .add("hit", hit.value())
                          .add("miss", miss.value())
                          .add("loadTotal", loadTotal.value())
                          .add("loadSuccess", loadSuccess.value())
                          .add("loadFailure", loadFailure.value())
                          .add("loadDuration", loadDuration.value())
                          .add("eviction", eviction.value())
                          .add("evictionWeight", evictionWeight.value())
                          .add("estimatedSize", estimatedSizeGauge.value())
                          .toString();
    }

    private final class CacheStatGauge extends SupplierGauge {

        CacheStatGauge(MetricKey key, MetricUnit unit, String description,
                       LongSupplier valueSupplier) {
            super(key, unit, description, valueSupplier);
        }

        @Override
        public long value() {
            updateCacheStats();
            return super.value();
        }
    }
}
