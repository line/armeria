/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.metric;

/**
 * A snapshot of the distribution represented by {@link Histogram}.
 */
public interface HistogramSnapshot {

    /**
     * Returns the value at the specified quantile.
     */
    double value(double quantile);

    /**
     * Returns the values in the snapshot.
     */
    long[] values();

    /**
     * Returns the number of values in the snapshot.
     */
    int size();

    /**
     * Returns the value at the 50th percentile (median).
     */
    default double p50() {
        return value(0.5);
    }

    /**
     * Returns the value at the 75th percentile.
     */
    default double p75() {
        return value(0.75);
    }

    /**
     * Returns the value at the 95th percentile.
     */
    default double p95() {
        return value(0.95);
    }

    /**
     * Returns the value at the 98th percentile.
     */
    default double p98() {
        return value(0.98);
    }

    /**
     * Returns the value at the 99th percentile.
     */
    default double p99() {
        return value(0.99);
    }

    /**
     * Returns the value at the 99.9th percentile.
     */
    default double p999() {
        return value(0.999);
    }

    /**
     * Returns the minimum.
     */
    long min();

    /**
     * Returns the maximum.
     */
    long max();

    /**
     * Returns the mean (average).
     */
    double mean();

    /**
     * Returns the standard deviation.
     */
    double stdDev();
}
