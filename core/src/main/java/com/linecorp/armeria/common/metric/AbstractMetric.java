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

import static java.util.Objects.requireNonNull;

/**
 * A skeletal {@link Metric} implementation.
 */
abstract class AbstractMetric implements Metric {

    private final MetricKey key;
    private final String description;
    private final MetricUnit unit;

    /**
     * Creates a new instance with the specified {@link MetricKey}, {@link MetricUnit} and description.
     */
    protected AbstractMetric(MetricKey key, MetricUnit unit, String description) {
        this.key = requireNonNull(key, "key");
        this.unit = requireNonNull(unit, "unit");
        this.description = requireNonNull(description, "description");
    }

    @Override
    public final MetricKey key() {
        return key;
    }

    @Override
    public final MetricUnit unit() {
        return unit;
    }

    @Override
    public final String description() {
        return description;
    }
}
