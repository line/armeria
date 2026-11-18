/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheWriter;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Limits the cardinality of request metrics by capping the number of distinct active request
 * {@link MeterIdPrefix}es.
 */
public final class RequestMetricLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RequestMetricLimiter.class);

    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);

    private final Cache<MeterIdPrefix, Entry> seenPrefixes;
    private final int maxCardinality;
    private volatile boolean warned;

    /**
     * Creates a new instance that forgets prefixes not accepted for the default idle timeout (10 minutes).
     */
    public RequestMetricLimiter(int maxCardinality) {
        this(maxCardinality, DEFAULT_IDLE_TIMEOUT);
    }

    /**
     * Creates a new instance that forgets prefixes not accepted for the specified {@code idleTimeout}.
     */
    public RequestMetricLimiter(int maxCardinality, Duration idleTimeout) {
        this(maxCardinality, idleTimeout, Ticker.systemTicker());
    }

    @VisibleForTesting
    RequestMetricLimiter(int maxCardinality, Duration idleTimeout, Ticker ticker) {
        checkArgument(maxCardinality > 0, "maxCardinality: %s (expected: > 0)", maxCardinality);
        requireNonNull(idleTimeout, "idleTimeout");
        checkArgument(!idleTimeout.isZero() && !idleTimeout.isNegative(),
                      "idleTimeout: %s (expected: > 0)", idleTimeout);
        requireNonNull(ticker, "ticker");
        this.maxCardinality = maxCardinality;
        seenPrefixes = Caffeine.newBuilder()
                               .ticker(ticker)
                               .expireAfterAccess(idleTimeout)
                               .writer(new CacheWriter<MeterIdPrefix, Entry>() {
                                   @Override
                                   public void write(MeterIdPrefix key, Entry value) {
                                       // No-op
                                   }

                                   @Override
                                   public void delete(MeterIdPrefix key, @Nullable Entry value,
                                                      RemovalCause cause) {
                                       if (value != null && cause.wasEvicted()) {
                                           value.evict();
                                       }
                                   }
                               })
                               .build();
    }

    /**
     * Returns the maximum cardinality.
     */
    public int maxCardinality() {
        return maxCardinality;
    }

    /**
     * Returns {@code true} if the request metric for the specified {@code prefix} is accepted.
     */
    @VisibleForTesting
    public boolean accept(MeterIdPrefix prefix) {
        return acquire(prefix) != null;
    }

    /**
     * Returns an {@link Entry} if the request metric for the specified {@code prefix} is accepted,
     * or {@code null} if the cardinality limit has been exceeded.
     */
    @Nullable
    public Entry acquire(MeterIdPrefix prefix) {
        requireNonNull(prefix, "prefix");
        Entry entry = seenPrefixes.getIfPresent(prefix);
        if (entry != null) {
            return entry;
        }
        seenPrefixes.cleanUp();
        if (seenPrefixes.estimatedSize() >= maxCardinality) {
            warn(prefix);
            return null;
        }
        entry = new Entry();
        final Entry existing = seenPrefixes.asMap().putIfAbsent(prefix, entry);
        return existing != null ? existing : entry;
    }

    private void warn(MeterIdPrefix prefix) {
        if (!warned) {
            warned = true;
            logger.warn("The number of distinct active request metrics for '{}' has exceeded " +
                        "the maximum cardinality ({}). Refusing to record request metrics " +
                        "for new tag combinations.", prefix.name(), maxCardinality);
        }
    }

    /**
     * Returns the set of seen prefixes.
     */
    @VisibleForTesting
    public Set<MeterIdPrefix> seenPrefixes() {
        seenPrefixes.cleanUp();
        return Collections.unmodifiableSet(seenPrefixes.asMap().keySet());
    }

    /**
     * Tracks resources (e.g., {@link Meter}s and {@link MicrometerUtil} entries) registered
     * for an accepted {@link MeterIdPrefix} so they can be cleaned up on eviction.
     */
    public static final class Entry {

        private final ConcurrentMap<RegisteredKey, Runnable> cleanups = new ConcurrentHashMap<>();
        private volatile boolean evicted;

        Entry() {}

        /**
         * Registers a cleanup task for the given {@code registry} and {@code idPrefix}.
         * If this entry has already been evicted, the {@code cleanup} runs immediately.
         */
        public void track(MeterRegistry registry, MeterIdPrefix idPrefix, Runnable cleanup) {
            requireNonNull(registry, "registry");
            requireNonNull(idPrefix, "idPrefix");
            requireNonNull(cleanup, "cleanup");
            final RegisteredKey key = new RegisteredKey(registry, idPrefix);
            if (evicted) {
                cleanup.run();
                return;
            }
            if (cleanups.putIfAbsent(key, cleanup) == null && evicted) {
                final Runnable removed = cleanups.remove(key);
                if (removed != null) {
                    removed.run();
                }
            }
        }

        /**
         * Returns {@code true} if this entry has been evicted.
         */
        public boolean isEvicted() {
            return evicted;
        }

        void evict() {
            evicted = true;
            for (RegisteredKey key : cleanups.keySet()) {
                final Runnable cleanup = cleanups.remove(key);
                if (cleanup != null) {
                    cleanup.run();
                }
            }
        }
    }

    private static final class RegisteredKey {
        private final MeterRegistry registry;
        private final MeterIdPrefix idPrefix;

        RegisteredKey(MeterRegistry registry, MeterIdPrefix idPrefix) {
            this.registry = registry;
            this.idPrefix = idPrefix;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RegisteredKey)) {
                return false;
            }
            final RegisteredKey other = (RegisteredKey) obj;
            return registry == other.registry && idPrefix.equals(other.idPrefix);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(registry) + idPrefix.hashCode();
        }
    }
}
