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

import static java.util.Objects.requireNonNull;

import java.util.function.DoubleSupplier;

/**
 * A {@link DoubleGauge} based on a {@link DoubleSupplier}.
 */
public class SupplierDoubleGauge extends AbstractDoubleGauge {

    private final DoubleSupplier valueSupplier;

    /**
     * Creates a new instance with the specified {@link MetricKey}, {@link MetricUnit}, description and
     * value supplier.
     */
    public SupplierDoubleGauge(MetricKey key, MetricUnit unit, String description,
                               DoubleSupplier valueSupplier) {
        super(key, unit, description);
        this.valueSupplier = requireNonNull(valueSupplier, "valueSupplier");
    }

    @Override
    public double value() {
        return valueSupplier.getAsDouble();
    }
}
