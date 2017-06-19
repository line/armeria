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

package com.linecorp.armeria.common.metric;

/**
 * A {@link MetricGroup} of a cache.
 */
public interface CacheMetrics extends MetricGroup {

    /**
     * Returns the number of cache hits and misses.
     */
    Gauge total();

    /**
     * Returns the number of cache hits.
     */
    Gauge hit();

    /**
     * Returns the number of cache misses.
     */
    Gauge miss();

    /**
     * Returns the number of cache loads (success and failure).
     */
    Gauge loadTotal();

    /**
     * Returns the number of successful cache loads.
     */
    Gauge loadSuccess();

    /**
     * Returns the number of failed cache loads.
     */
    Gauge loadFailure();

    /**
     * Returns the total load time (success and failure).
     */
    Gauge loadDuration();

    /**
     * Returns the number of evicted entries.
     */
    Gauge eviction();

    /**
     * Returns the sum of weights of evicted entries.
     */
    Gauge evictionWeight();

    /**
     * Returns the approximate number of entries.
     */
    Gauge estimatedSize();
}
