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

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

/**
 * Limits the cardinality of request metrics by capping the number of distinct {@link MeterIdPrefix}es.
 */
public final class RequestMetricLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RequestMetricLimiter.class);

    private final Set<MeterIdPrefix> seenActivePrefixes = ConcurrentHashMap.newKeySet();
    private final Set<MeterIdPrefix> seenCompletePrefixes = ConcurrentHashMap.newKeySet();
    private final int maxCardinality;
    private final AtomicBoolean warnedActive = new AtomicBoolean();
    private final AtomicBoolean warnedComplete = new AtomicBoolean();

    /**
     * Creates a new instance with the specified {@code maxCardinality}.
     */
    public RequestMetricLimiter(int maxCardinality) {
        checkArgument(maxCardinality > 0, "maxCardinality: %s (expected: > 0)", maxCardinality);
        this.maxCardinality = maxCardinality;
    }

    /**
     * Returns the maximum cardinality.
     */
    public int maxCardinality() {
        return maxCardinality;
    }

    /**
     * Returns {@code true} if the active request metric for the specified {@code prefix} is accepted.
     */
    public boolean acceptActive(MeterIdPrefix prefix) {
        if (seenActivePrefixes.contains(prefix)) {
            return true;
        }
        if (seenActivePrefixes.size() >= maxCardinality) {
            warnActive(prefix);
            return false;
        }
        if (seenActivePrefixes.add(prefix)) {
            if (seenActivePrefixes.size() > maxCardinality) {
                seenActivePrefixes.remove(prefix);
                warnActive(prefix);
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if the completed request metric for the specified {@code prefix} is accepted.
     */
    public boolean acceptComplete(MeterIdPrefix prefix) {
        if (seenCompletePrefixes.contains(prefix)) {
            return true;
        }
        if (seenCompletePrefixes.size() >= maxCardinality) {
            warnComplete(prefix);
            return false;
        }
        if (seenCompletePrefixes.add(prefix)) {
            if (seenCompletePrefixes.size() > maxCardinality) {
                seenCompletePrefixes.remove(prefix);
                warnComplete(prefix);
                return false;
            }
        }
        return true;
    }

    private void warnActive(MeterIdPrefix prefix) {
        if (warnedActive.compareAndSet(false, true)) {
            logger.warn("The number of distinct active request metrics for '{}' has exceeded " +
                        "the maximum cardinality ({}). Refusing to record active request metrics " +
                        "for new tag combinations.", prefix.name(), maxCardinality);
        }
    }

    private void warnComplete(MeterIdPrefix prefix) {
        if (warnedComplete.compareAndSet(false, true)) {
            logger.warn("The number of distinct request metrics for '{}' has exceeded " +
                        "the maximum cardinality ({}). Refusing to record request metrics " +
                        "for new tag combinations.", prefix.name(), maxCardinality);
        }
    }

    /**
     * Returns the set of seen active prefixes.
     */
    @VisibleForTesting
    public Set<MeterIdPrefix> seenActivePrefixes() {
        return Collections.unmodifiableSet(seenActivePrefixes);
    }

    /**
     * Returns the set of seen complete prefixes.
     */
    @VisibleForTesting
    public Set<MeterIdPrefix> seenCompletePrefixes() {
        return Collections.unmodifiableSet(seenCompletePrefixes);
    }
}
