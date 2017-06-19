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

import io.micrometer.core.instrument.Meter;

/**
 * Unit of a {@link Meter}.
 */
public enum MeterUnit {
    /**
     * No unit.
     */
    NONE("", false),
    /**
     * No unit, but cumulative.
     */
    NONE_TOTAL("", true),
    /**
     * The number of bytes.
     */
    BYTES("bytes", false),
    /**
     * The cumulative number of bytes.
     */
    BYTES_TOTAL("bytes", true),
    /**
     * The duration in nanoseconds.
     */
    DURATION("seconds", false),
    /**
     * The cumulative duration in nanoseconds.
     */
    DURATION_TOTAL("seconds", true);

    private final String baseUnitName;
    private final boolean isTotal;

    MeterUnit(String baseUnitName, boolean isTotal) {
        this.baseUnitName = baseUnitName;
        this.isTotal = isTotal;
    }

    /**
     * Returns the base unit name of this unit.
     */
    public String baseUnitName() {
        return baseUnitName;
    }

    /**
     * Returns whether this unit is cumulative or not.
     */
    public boolean isTotal() {
        return isTotal;
    }
}
