/*
 * Copyright 2018 LINE Corporation
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

import static com.linecorp.armeria.common.metric.DropwizardMeterRegistries.DEFAULT_DROPWIZARD_CONFIG;
import static com.linecorp.armeria.common.metric.DropwizardMeterRegistries.DEFAULT_NAME_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableList;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;

public class DropwizardMeterRegistriesTest {
    @Test
    public void micrometerAddsUnwantedGauges() {
        final DropwizardMeterRegistry micrometer = new DropwizardMeterRegistry(
                DEFAULT_DROPWIZARD_CONFIG, new MetricRegistry(), DEFAULT_NAME_MAPPER, Clock.SYSTEM) {
            @Override
            protected Double nullGaugeValue() {
                return 0.0;
            }
        };
        final MetricRegistry dropwizard = micrometer.getDropwizardRegistry();

        final DistributionSummary percentileSummary = DistributionSummary.builder("percentileSummary")
                                                                         .publishPercentiles(0.5)
                                                                         .register(micrometer);
        final DistributionSummary histogramSummary = DistributionSummary.builder("histogramSummary")
                                                                        .sla(10)
                                                                        .register(micrometer);
        final Timer percentileTimer = Timer.builder("percentileTimer")
                                           .publishPercentiles(0.5)
                                           .register(micrometer);
        final Timer histogramTimer = Timer.builder("histogramTimer")
                                          .sla(Duration.ofSeconds(10))
                                          .register(micrometer);
        percentileSummary.record(42);
        histogramSummary.record(42);
        percentileTimer.record(42, TimeUnit.SECONDS);
        histogramTimer.record(42, TimeUnit.SECONDS);

        // Make sure Micrometer by default registers the unwanted gauges.
        final Map<String, Double> measurements = MoreMeters.measureAll(micrometer);
        assertThat(measurements).containsKeys(
                "percentileSummary.percentile#value{phi=0.5}",
                "histogramSummary.histogram#value{le=10}",
                "percentileTimer.percentile#value{phi=0.5}",
                "histogramTimer.histogram#value{le=10000}");

        assertThat(dropwizard.getGauges()).containsKeys(
                "percentileSummaryPercentile.phi:0.5",
                "histogramSummaryHistogram.le:10",
                "percentileTimerPercentile.phi:0.5",
                "histogramTimerHistogram.le:10000");
    }

    @Test
    public void unwantedGaugesAreFilteredOut() {
        final DropwizardMeterRegistry micrometer = DropwizardMeterRegistries.newRegistry();
        final MetricRegistry dropwizard = micrometer.getDropwizardRegistry();

        final DistributionSummary percentileSummary = DistributionSummary.builder("percentileSummary")
                                                                         .publishPercentiles(0.5, 0.99)
                                                                         .register(micrometer);
        final DistributionSummary histogramSummary = DistributionSummary.builder("histogramSummary")
                                                                        .sla(10, 100)
                                                                        .register(micrometer);
        final Timer percentileTimer = Timer.builder("percentileTimer")
                                           .publishPercentiles(0.5, 0.99)
                                           .register(micrometer);
        final Timer histogramTimer = Timer.builder("histogramTimer")
                                          .sla(Duration.ofSeconds(10), Duration.ofSeconds(100))
                                          .register(micrometer);
        percentileSummary.record(42);
        histogramSummary.record(42);
        percentileTimer.record(42, TimeUnit.SECONDS);
        histogramTimer.record(42, TimeUnit.SECONDS);

        final Map<String, Double> measurements = MoreMeters.measureAll(micrometer);
        measurements.forEach((key, value) -> assertThat(key).doesNotContain(".percentile")
                                                            .doesNotContain(".histogram")
                                                            .doesNotContain("phi=")
                                                            .doesNotContain("le="));

        // Must be exported as 2 Histograms and 2 Timers only.
        assertThat(dropwizard.getHistograms()).hasSize(2);
        assertThat(dropwizard.getTimers()).hasSize(2);
    }

    @Test
    public void filteredGaugesDoNotAffectOthers() {
        final CompositeMeterRegistry micrometer = new CompositeMeterRegistry();
        final PrometheusMeterRegistry prometheus = PrometheusMeterRegistries.newRegistry();
        final DropwizardMeterRegistry dropwizard = DropwizardMeterRegistries.newRegistry();
        micrometer.add(prometheus).add(dropwizard);
        final DistributionSummary summary = DistributionSummary.builder("summary")
                                                               .publishPercentiles(0.5, 0.99)
                                                               .register(micrometer);
        summary.record(42);

        // Make sure Dropwizard registry does not have unwanted gauges.
        assertThat(dropwizard.getDropwizardRegistry().getMetrics()).containsOnlyKeys("summary");

        // Make sure Prometheus registry collects all samples.
        final MetricFamilySamples prometheusSamples = findPrometheusSample(prometheus, "summary");
        assertThat(prometheusSamples.samples).containsExactly(
                new Sample("summary", ImmutableList.of("quantile"), ImmutableList.of("0.5"), 42),
                new Sample("summary", ImmutableList.of("quantile"), ImmutableList.of("0.99"), 42),
                new Sample("summary_count", ImmutableList.of(), ImmutableList.of(), 1),
                new Sample("summary_sum", ImmutableList.of(), ImmutableList.of(), 42));
    }

    private static MetricFamilySamples findPrometheusSample(PrometheusMeterRegistry registry, String name) {
        for (final Enumeration<MetricFamilySamples> e = registry.getPrometheusRegistry().metricFamilySamples();
             e.hasMoreElements();) {
            final MetricFamilySamples samples = e.nextElement();
            if (name.equals(samples.name)) {
                return samples;
            }
        }

        fail("Could not find a Prometheus sample: " + name);
        throw new Error(); // Never reaches here.
    }
}
