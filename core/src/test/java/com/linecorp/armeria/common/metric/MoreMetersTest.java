/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.Test;

import io.micrometer.core.instrument.MeterRegistry;

public class MoreMetersTest {
    private static final String ESTIMATED_FOOTPRINT = "armeria.hdrHistogram.estimatedFootprint#value";
    private static final String COUNT = "armeria.hdrHistogram.count#value";

    @Test
    public void hdrHistogramMeters() {
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();

        // Register a distribution summary.
        MoreMeters.summaryWithDefaultQuantiles(registry, new MeterId("foo.bar"));

        // Check if estimated footprint is recorded.
        final Map<String, Double> measurement1 = MoreMeters.measureAll(registry);
        assertThat(measurement1).containsEntry(COUNT, 1.0).containsKey(ESTIMATED_FOOTPRINT);
        final double footprint1 = measurement1.get(ESTIMATED_FOOTPRINT);
        assertThat(footprint1).isPositive();

        // Register a timer.
        MoreMeters.timerWithDefaultQuantiles(registry, new MeterId("alice.bob"));

        // Check if estimated footprint has increased, because we created two Quantiles.
        final Map<String, Double> measurement2 = MoreMeters.measureAll(registry);
        assertThat(measurement2).containsEntry(COUNT, 2.0).containsKey(ESTIMATED_FOOTPRINT);
        assertThat(measurement2.get(ESTIMATED_FOOTPRINT)).isGreaterThan(footprint1);
    }
}
