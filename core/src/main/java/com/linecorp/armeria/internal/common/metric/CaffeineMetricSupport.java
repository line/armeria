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

package com.linecorp.armeria.internal.common.metric;

import static com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport.Type.EVICTION_COUNT;
import static com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport.Type.EVICTION_WEIGHT;
import static com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport.Type.HIT_COUNT;
import static com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport.Type.LOAD_FAILURE_COUNT;
import static com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport.Type.LOAD_SUCCESS_COUNT;
import static com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport.Type.MISS_COUNT;
import static com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport.Type.TOTAL_LOAD_TIME;
import static java.util.Objects.requireNonNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.Ticker;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers the stats of Caffeine {@link Cache}.
 */
public final class CaffeineMetricSupport {

    @VisibleForTesting
    static final long UPDATE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3);

    public static void setup(MeterRegistry registry, MeterIdPrefix idPrefix, Cache<?, ?> cache) {
        setup(registry, idPrefix, cache, Ticker.systemTicker());
    }

    public static void setup(MeterRegistry registry, MeterIdPrefix idPrefix, Cache<?, ?> cache, Ticker ticker) {
        requireNonNull(cache, "cache");
        if (!cache.policy().isRecordingStats()) {
            return;
        }
        final CaffeineMetrics metrics = MicrometerUtil.register(
                registry, idPrefix, CaffeineMetrics.class, CaffeineMetrics::new);
        metrics.add(cache, ticker);
    }

    private CaffeineMetricSupport() {}

    enum Type {
        HIT_COUNT,
        MISS_COUNT,
        EVICTION_COUNT,
        EVICTION_WEIGHT,
        LOAD_SUCCESS_COUNT,
        LOAD_FAILURE_COUNT,
        TOTAL_LOAD_TIME;

        static final int count = values().length;
    }

    private static final class CaffeineMetrics {

        private final MeterRegistry parent;
        private final MeterIdPrefix idPrefix;
        private final List<CacheReference> cacheRefs = new ArrayList<>(2);
        private final AtomicBoolean hasLoadingCache = new AtomicBoolean();

        /**
         * An array whose each element is the sum of the garbage-collected {@link Cache} stats.
         * {@link Type#ordinal()} signifies the index of this array.
         */
        private final double[] statsForGarbageCollected = new double[Type.count];

        CaffeineMetrics(MeterRegistry parent, MeterIdPrefix idPrefix) {
            this.parent = requireNonNull(parent, "parent");
            this.idPrefix = requireNonNull(idPrefix, "idPrefix");

            final String requests = idPrefix.name("requests");
            parent.more().counter(requests, idPrefix.tags("result", "hit"), this,
                                  func(HIT_COUNT, ref -> ref.cacheStats.hitCount()));
            parent.more().counter(requests, idPrefix.tags("result", "miss"), this,
                                  func(MISS_COUNT, ref -> ref.cacheStats.missCount()));
            parent.more().counter(idPrefix.name("evictions"), idPrefix.tags(), this,
                                  func(EVICTION_COUNT, ref -> ref.cacheStats.evictionCount()));
            parent.more().counter(idPrefix.name("eviction.weight"), idPrefix.tags(), this,
                                  func(EVICTION_WEIGHT, ref -> ref.cacheStats.evictionWeight()));
            parent.gauge(idPrefix.name("estimated.size"), idPrefix.tags(), this,
                         func(null, ref -> ref.estimatedSize));
        }

        void add(Cache<?, ?> cache, Ticker ticker) {
            synchronized (cacheRefs) {
                for (CacheReference ref : cacheRefs) {
                    if (ref.get() == cache) {
                        // Do not aggregate more than once for the same instance.
                        return;
                    }
                }

                cacheRefs.add(new CacheReference(cache, ticker));
            }

            if (cache instanceof LoadingCache && hasLoadingCache.compareAndSet(false, true)) {
                // Add the following meters only for LoadingCache and only once.
                final String loads = idPrefix.name("loads");

                parent.more().counter(loads, idPrefix.tags("result", "success"), this,
                                      func(LOAD_SUCCESS_COUNT, ref -> ref.cacheStats.loadSuccessCount()));
                parent.more().counter(loads, idPrefix.tags("result", "failure"), this,
                                      func(LOAD_FAILURE_COUNT, ref -> ref.cacheStats.loadFailureCount()));
                parent.more().counter(idPrefix.name("load.duration"), idPrefix.tags(), this,
                                      func(TOTAL_LOAD_TIME, ref -> ref.cacheStats.totalLoadTime()));
            }
        }

        private ToDoubleFunction<CaffeineMetrics> func(@Nullable Type type,
                                                       ToDoubleFunction<CacheReference> valueFunction) {
            return value -> {
                double sum = 0;
                synchronized (cacheRefs) {
                    for (final Iterator<CacheReference> i = cacheRefs.iterator(); i.hasNext();) {
                        final CacheReference ref = i.next();
                        final boolean garbageCollected = ref.updateCacheStats();
                        if (!garbageCollected) {
                            sum += valueFunction.applyAsDouble(ref);
                        } else {
                            // Remove the garbage-collected reference from the list to prevent it from
                            // growing infinitely.
                            i.remove();

                            // Accumulate the stats of the removed reference so the counters do not decrease.
                            // NB: We do not accumulate 'estimatedSize' because it's not a counter but a gauge.
                            final CacheStats stats = ref.cacheStats;
                            statsForGarbageCollected[HIT_COUNT.ordinal()] += stats.hitCount();
                            statsForGarbageCollected[MISS_COUNT.ordinal()] += stats.missCount();
                            statsForGarbageCollected[EVICTION_COUNT.ordinal()] += stats.evictionCount();
                            statsForGarbageCollected[EVICTION_WEIGHT.ordinal()] += stats.evictionWeight();
                            statsForGarbageCollected[LOAD_SUCCESS_COUNT.ordinal()] += stats.loadSuccessCount();
                            statsForGarbageCollected[LOAD_FAILURE_COUNT.ordinal()] += stats.loadFailureCount();
                            statsForGarbageCollected[TOTAL_LOAD_TIME.ordinal()] += stats.totalLoadTime();
                        }
                    }

                    if (type != null) {
                        // Add the value of the garbage-collected caches.
                        sum += statsForGarbageCollected[type.ordinal()];
                    }
                }

                return sum;
            };
        }
    }

    private static final class CacheReference extends WeakReference<Cache<?, ?>> {

        private final Ticker ticker;
        private volatile long lastStatsUpdateTime;
        private CacheStats cacheStats = CacheStats.empty();
        private long estimatedSize;

        CacheReference(Cache<?, ?> cache, Ticker ticker) {
            super(requireNonNull(cache, "cache"));
            this.ticker = requireNonNull(ticker, "ticker");
            updateCacheStats(true);
        }

        boolean updateCacheStats() {
            return updateCacheStats(false);
        }

        private boolean updateCacheStats(boolean force) {
            final Cache<?, ?> cache = get();
            if (cache == null) {
                return true; // GC'd
            }

            final long currentTimeNanos = ticker.read();
            if (!force) {
                if (currentTimeNanos - lastStatsUpdateTime < UPDATE_INTERVAL_NANOS) {
                    return false; // Not GC'd
                }
            }

            cacheStats = cache.stats();
            estimatedSize = cache.estimatedSize();

            // Write the volatile field last so that cacheStats and estimatedSize are visible
            // after reading the volatile field.
            lastStatsUpdateTime = currentTimeNanos;
            return false; // Not GC'd
        }
    }
}
