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

import static com.linecorp.armeria.common.metric.MetricUnit.COUNT;
import static com.linecorp.armeria.common.metric.MetricUnit.COUNT_CUMULATIVE;
import static com.linecorp.armeria.common.metric.MetricUnit.NANOSECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.Test;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class DropwizardMetricsExporterTest {

    private static final List<String> EMPTY = ImmutableList.of();

    @Test
    public void constructorValidation() {
        final MetricRegistry registry = new MetricRegistry();

        // Instantiation with non-default collectorRegistry.
        new DropwizardMetricsExporter(registry);

        // collectorRegistry is null.
        assertThatThrownBy(() -> new DropwizardMetricsExporter(null))
                .isInstanceOf(NullPointerException.class);

        // prefix is null.
        assertThatThrownBy(() -> new DropwizardMetricsExporter(registry, (String[]) null))
                .isInstanceOf(NullPointerException.class);

        // prefix contains null.
        assertThatThrownBy(() -> new DropwizardMetricsExporter(registry, "foo", null))
                .isInstanceOf(NullPointerException.class);

        // prefix contains an empty string.
        assertThatThrownBy(() -> new DropwizardMetricsExporter(registry, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void exportGauge() {
        final MetricRegistry registry = new MetricRegistry();
        new DropwizardMetricsExporter(registry).export(new SupplierGauge(
                new MetricKey("foo", "bar"), COUNT, "", () -> 42));

        @SuppressWarnings("unchecked")
        final Gauge<Long> gauge = registry.getGauges().get("foo.bar");
        assertThat(gauge).isNotNull();
        assertThat(gauge.getValue()).isEqualTo(42);
    }

    @Test
    public void exportDoubleGauge() {
        final MetricRegistry registry = new MetricRegistry();
        new DropwizardMetricsExporter(registry).export(new SupplierDoubleGauge(
                new MetricKey("alice", "bob"), COUNT_CUMULATIVE, "", () -> 42.0));

        @SuppressWarnings("unchecked")
        final Gauge<Double> gauge = registry.getGauges().get("alice.bob");
        assertThat(gauge).isNotNull();
        assertThat(gauge.getValue()).isEqualTo(42.0);
    }

    @Test
    public void exportHistogram() {
        final MetricRegistry registry = new MetricRegistry();
        final DefaultHistogram histogram = new DefaultHistogram(
                new MetricKey("foo", "bar"), NANOSECONDS, "baz");
        new DropwizardMetricsExporter(registry).export(histogram);

        histogram.update(100);
        histogram.update(200);
        final HistogramSnapshot snap = histogram.snapshot();

        final String expectedName = "foo.bar";
        final Histogram dropwizardHistogram = registry.getHistograms().get(expectedName);
        assertThat(dropwizardHistogram).isNotNull();
        final Snapshot dropwizardSnap = dropwizardHistogram.getSnapshot();

        assertThat(dropwizardSnap.getMin()).isEqualTo(snap.min());
        assertThat(dropwizardSnap.getMean()).isEqualTo(snap.mean());
        assertThat(dropwizardSnap.getMax()).isEqualTo(snap.max());
        assertThat(dropwizardSnap.getStdDev()).isEqualTo(snap.stdDev());
        assertThat(dropwizardSnap.get75thPercentile()).isEqualTo(snap.p75());
        assertThat(dropwizardSnap.get95thPercentile()).isEqualTo(snap.p95());
        assertThat(dropwizardSnap.get98thPercentile()).isEqualTo(snap.p98());
        assertThat(dropwizardSnap.get99thPercentile()).isEqualTo(snap.p99());
        assertThat(dropwizardSnap.get999thPercentile()).isEqualTo(snap.p999());
        assertThat(dropwizardSnap.getValues()).containsExactly(snap.values());
        assertThat(dropwizardSnap.size()).isEqualTo(snap.size());
        assertThat(dropwizardHistogram.getCount()).isEqualTo(histogram.count());
    }

    @Test
    public void exportUnknown() {
        final MetricRegistry registry = new MetricRegistry();
        final MetricsExporter exporter = new DropwizardMetricsExporter(registry);
        assertThatThrownBy(() -> exporter.export(mock(Metric.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void nameConversion() {
        final MetricRegistry registry = new MetricRegistry();
        final DropwizardMetricsExporter exporter = new DropwizardMetricsExporter(registry, "foo");

        // No conversion or sanitization is made.
        assertThat(exporter.dropwizardName(new MetricKey("bar"))).isEqualTo("foo.bar");
        assertThat(exporter.dropwizardName(new MetricKey("bar.baz"))).isEqualTo("foo.bar.baz");
        assertThat(exporter.dropwizardName(new MetricKey("barBaz0"))).isEqualTo("foo.barBaz0");
        assertThat(exporter.dropwizardName(new MetricKey("bar_Baz"))).isEqualTo("foo.bar_Baz");
        assertThat(exporter.dropwizardName(new MetricKey("/home"))).isEqualTo("foo./home");

        // Accept empty name if prefix is not empty.
        assertThat(exporter.dropwizardName(new MetricKey(EMPTY, ImmutableMap.of("bar", "baz"))))
                .isEqualTo("foo.labels{bar=baz}");

        // Do not accept empty name if prefix is empty.
        assertThatThrownBy(() -> new DropwizardMetricsExporter(registry).dropwizardName(
                new MetricKey(EMPTY, ImmutableMap.of("bar", "baz"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
