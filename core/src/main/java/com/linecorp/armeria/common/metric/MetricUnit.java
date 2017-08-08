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
 * Unit of a {@link Metric}.
 */
public enum MetricUnit {
    /**
     * The number of occurrences.
     */
    COUNT("", false),
    /**
     * The cumulative number of occurrences.
     */
    COUNT_CUMULATIVE("", true),
    /**
     * The number of bytes.
     */
    BYTES("bytes", false),
    /**
     * The cumulative number of bytes.
     */
    BYTES_CUMULATIVE("bytes", true),
    /**
     * The duration in nanoseconds.
     */
    NANOSECONDS("ns", false),
    /**
     * The cumulative duration in nanoseconds.
     */
    NANOSECONDS_CUMULATIVE("ns", true);

    private final String suffix;
    private final boolean isCumulative;

    MetricUnit(String suffix, boolean isCumulative) {
        if (isCumulative) {
            if (suffix.isEmpty()) {
                suffix = "cumulative";
            } else {
                suffix = "cumulative_" + suffix;
            }
        }
        this.suffix = suffix;
        this.isCumulative = isCumulative;
    }

    /**
     * Returns the human-readable suffix string of this unit.
     */
    public String suffix() {
        return suffix;
    }

    /**
     * Returns whether this unit is cumulative or not.
     */
    public boolean isCumulative() {
        return isCumulative;
    }
}
