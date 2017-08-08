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
 * A skeletal {@link MetricsExporter} implementation.
 */
public abstract class AbstractMetricsExporter implements MetricsExporter {

    /**
     * {@inheritDoc} This method forwards the given {@link Metric} to an appropriate abstract {@code export()}
     * method in this class.
     *
     * @throws IllegalArgumentException if the specified {@link Metric} is not supported
     */
    @Override
    public final void export(Metric metric) {
        requireNonNull(metric, "metric");

        if (metric instanceof Gauge) {
            exportGauge((Gauge) metric);
        } else if (metric instanceof DoubleGauge) {
            exportDoubleGauge((DoubleGauge) metric);
        } else if (metric instanceof Histogram) {
            exportHistogram((Histogram) metric);
        } else {
            throw new IllegalArgumentException("unsupported metric type: " + metric.getClass().getName());
        }
    }

    /**
     * Exports the specified {@link Gauge}.
     */
    protected abstract void exportGauge(Gauge gauge);

    /**
     * Exports the specified {@link DoubleGauge}.
     */
    protected abstract void exportDoubleGauge(DoubleGauge gauge);

    /**
     * Exports the specified {@link Histogram}.
     */
    protected abstract void exportHistogram(Histogram histogram);
}
