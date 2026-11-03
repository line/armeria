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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

/**
 * Limits the cardinality of request metrics by capping the number of distinct active request
 * {@link MeterIdPrefix}es.
 */
public final class RequestMetricLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RequestMetricLimiter.class);

    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);

    private final Cache<MeterIdPrefix, Boolean> seenPrefixes;
    private final int maxCardinality;
    private boolean warned;

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
    public boolean accept(MeterIdPrefix prefix) {
        if (seenPrefixes.getIfPresent(prefix) != null) {
            return true;
        }
        seenPrefixes.cleanUp();
        if (seenPrefixes.estimatedSize() >= maxCardinality) {
            warn(prefix);
            return false;
        }
        seenPrefixes.put(prefix, Boolean.TRUE);
        return true;
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
}
