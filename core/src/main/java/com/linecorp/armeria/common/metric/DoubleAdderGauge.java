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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.atomic.DoubleAdder;

/**
 * A {@link DoubleGauge} based on {@link DoubleAdder}.
 */
public class DoubleAdderGauge extends AbstractDoubleGauge {

    private final DoubleAdder counter = new DoubleAdder();
    private final boolean allowDecrement;

    /**
     * Creates a new gauge which does not allow decrementing its value. Calling {@link #add(double)} with
     * a negative value on this gauge will trigger an {@link IllegalArgumentException} and {@link #value()}
     * will never return a negative value.
     *
     * @param key the {@link MetricKey} of this gauge
     * @param unit the {@link MetricUnit} of this gauge
     * @param description the human-readable description of this gauge
     */
    public DoubleAdderGauge(MetricKey key, MetricUnit unit, String description) {
        this(key, unit, description, false);
    }

    /**
     * Creates a new gauge.
     *
     * @param key the {@link MetricKey} of this gauge
     * @param unit the {@link MetricUnit} of this gauge
     * @param description the human-readable description of this gauge
     * @param allowDecrement if {@code true}, it is allowed to call {@link #add(double)} with a negative value
     *                       and {@link #value()} can return a negative value.
     *                       If {@code false}, calling {@link #add(double)} with a negative value will trigger
     *                       an {@link IllegalArgumentException} and {@link #value()} will never return
     *                       a negative value.
     */
    public DoubleAdderGauge(MetricKey key, MetricUnit unit, String description, boolean allowDecrement) {
        super(key, unit, description);
        this.allowDecrement = allowDecrement;
    }

    @Override
    public double value() {
        return counter.sum();
    }

    /**
     * Increases the value of this gauge by the specified value.
     *
     * @throws IllegalArgumentException if this gauge does not allow decrement and
     *                                  the specified value is negative
     */
    public void add(double delta) {
        if (!allowDecrement) {
            checkArgument(delta >= 0, "delta: %s (expected: >= 0)", delta);
        }
        counter.add(delta);
    }
}
