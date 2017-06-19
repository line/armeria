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
 * A skeletal {@link DoubleGauge} implementation.
 */
public abstract class AbstractDoubleGauge extends AbstractMetric implements DoubleGauge {

    /**
     * Creates a new instance with the specified {@link MetricKey}, {@link MetricUnit} and description.
     */
    protected AbstractDoubleGauge(MetricKey key, MetricUnit unit, String description) {
        super(key, unit, description);
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder().append(key()).append('=').append(value());
        final String suffix = unit().suffix();
        if (!suffix.isEmpty()) {
            buf.append('(').append(suffix).append(')');
        }

        return buf.toString();
    }
}
