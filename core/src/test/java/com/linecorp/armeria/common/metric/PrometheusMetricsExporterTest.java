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

import static com.linecorp.armeria.common.metric.MetricUnit.BYTES;
import static com.linecorp.armeria.common.metric.MetricUnit.BYTES_CUMULATIVE;
import static com.linecorp.armeria.common.metric.MetricUnit.COUNT;
import static com.linecorp.armeria.common.metric.MetricUnit.COUNT_CUMULATIVE;
import static com.linecorp.armeria.common.metric.MetricUnit.NANOSECONDS;
import static com.linecorp.armeria.common.metric.MetricUnit.NANOSECONDS_CUMULATIVE;
import static com.linecorp.armeria.common.metric.PrometheusMetricsExporter.convertValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.Collector.Type;
import io.prometheus.client.CollectorRegistry;

public class PrometheusMetricsExporterTest {

    private static final List<String> EMPTY = ImmutableList.of();

    @Test
    public void constructorValidation() {

        // Instantiation with default collectorRegistry.
        new PrometheusMetricsExporter();

        // Instantiation with non-default collectorRegistry.
        new PrometheusMetricsExporter(new CollectorRegistry());

        // collectorRegistry is null.
        assertThatThrownBy(() -> new PrometheusMetricsExporter(null))
                .isInstanceOf(NullPointerException.class);

        // prefix is null.
        assertThatThrownBy(() -> new PrometheusMetricsExporter((String[]) null))
                .isInstanceOf(NullPointerException.class);

        // prefix contains null.
        assertThatThrownBy(() -> new PrometheusMetricsExporter("foo", null))
                .isInstanceOf(NullPointerException.class);

        // prefix contains an empty string.
        assertThatThrownBy(() -> new PrometheusMetricsExporter(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void exportGauge() {
        final CollectorRegistry registry = new CollectorRegistry();
        new PrometheusMetricsExporter(registry).export(new SupplierGauge(
                new MetricKey("foo", "bar"), COUNT, "baz", () -> 42));

        final List<MetricFamilySamples> samples = Collections.list(registry.metricFamilySamples());
        assertThat(samples).hasSize(1);

        final MetricFamilySamples mfs = samples.get(0);
        assertThat(mfs.name).isEqualTo("foo_bar");
        assertThat(mfs.type).isEqualTo(Type.GAUGE);
        assertThat(mfs.help).isEqualTo("baz");
        assertThat(mfs.samples).containsExactly(new Sample("foo_bar", EMPTY, EMPTY, 42.0));
    }

    @Test
    public void exportDoubleGauge() {
        final CollectorRegistry registry = new CollectorRegistry();
        new PrometheusMetricsExporter(registry).export(new SupplierDoubleGauge(
                new MetricKey("alice", "bob"), COUNT_CUMULATIVE, "charlie", () -> 42.0));

        final List<MetricFamilySamples> samples = Collections.list(registry.metricFamilySamples());
        assertThat(samples).hasSize(1);

        final MetricFamilySamples mfs = samples.get(0);
        assertThat(mfs.name).isEqualTo("alice_bob_total");
        assertThat(mfs.type).isEqualTo(Type.COUNTER);
        assertThat(mfs.help).isEqualTo("charlie");
        assertThat(mfs.samples).containsExactly(new Sample("alice_bob_total", EMPTY, EMPTY, 42.0));
    }

    @Test
    public void exportHistogram() {
        final CollectorRegistry registry = new CollectorRegistry();
        final DefaultHistogram histogram = new DefaultHistogram(
                new MetricKey("foo", "bar"), NANOSECONDS, "baz");
        new PrometheusMetricsExporter(registry).export(histogram);

        histogram.update(100);
        histogram.update(200);
        final HistogramSnapshot snap = histogram.snapshot();

        final String expectedName = "foo_bar_seconds";
        final List<String> expectedLabels = ImmutableList.of("quantile");
        final List<MetricFamilySamples> samples = Collections.list(registry.metricFamilySamples());
        assertThat(samples).hasSize(1);

        final MetricFamilySamples mfs = samples.get(0);
        assertThat(mfs.name).isEqualTo(expectedName);
        assertThat(mfs.type).isEqualTo(Type.SUMMARY);
        assertThat(mfs.help).isEqualTo("baz");

        final double ONE_SEC = TimeUnit.SECONDS.toNanos(1);
        assertThat(mfs.samples).containsExactly(
                new Sample(expectedName, expectedLabels, ImmutableList.of("0.0"), snap.min() / ONE_SEC),
                new Sample(expectedName, expectedLabels, ImmutableList.of("0.5"), snap.p50() / ONE_SEC),
                new Sample(expectedName, expectedLabels, ImmutableList.of("0.75"), snap.p75() / ONE_SEC),
                new Sample(expectedName, expectedLabels, ImmutableList.of("0.95"), snap.p95() / ONE_SEC),
                new Sample(expectedName, expectedLabels, ImmutableList.of("0.98"), snap.p98() / ONE_SEC),
                new Sample(expectedName, expectedLabels, ImmutableList.of("0.99"), snap.p99() / ONE_SEC),
                new Sample(expectedName, expectedLabels, ImmutableList.of("0.999"), snap.p999() / ONE_SEC),
                new Sample(expectedName, expectedLabels, ImmutableList.of("1.0"), snap.max() / ONE_SEC),
                new Sample("foo_bar_seconds_count", EMPTY, EMPTY, histogram.count()),
                new Sample("foo_bar_seconds_sum", EMPTY, EMPTY, histogram.sum() / ONE_SEC));
    }

    @Test
    public void exportUnknown() {
        final MetricsExporter exporter = new PrometheusMetricsExporter();
        assertThatThrownBy(() -> exporter.export(mock(Metric.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void twoMetricsWithSameNameAndDifferentLabels() {
        final CollectorRegistry registry = new CollectorRegistry();
        final MetricsExporter exporter = new PrometheusMetricsExporter(registry);
        exporter.export(new SupplierGauge(new MetricKey("a"), COUNT, "", () -> 1));
        exporter.export(new SupplierGauge(new MetricKey(ImmutableList.of("a"),
                                                        ImmutableMap.of("protocol", "H1")),
                                          COUNT, "", () -> 2));
        exporter.export(new SupplierGauge(new MetricKey(ImmutableList.of("a"),
                                                        ImmutableMap.of("protocol", "H2")),
                                          COUNT, "", () -> 3));

        final List<MetricFamilySamples> samples = Collections.list(registry.metricFamilySamples());
        assertThat(samples).hasSize(1);

        final MetricFamilySamples mfs = samples.get(0);
        assertThat(mfs.name).isEqualTo("a");
        assertThat(mfs.type).isEqualTo(Type.GAUGE);
        assertThat(mfs.samples).containsExactly(
                new Sample("a", EMPTY, EMPTY, 1.0),
                new Sample("a", ImmutableList.of("protocol"), ImmutableList.of("H1"), 2.0),
                new Sample("a", ImmutableList.of("protocol"), ImmutableList.of("H2"), 3.0));
    }

    @Test
    public void valueConversion() {
        // Make sure a nanosecond value is converted to a second value.
        assertThat(convertValue(1000000000.0, NANOSECONDS)).isEqualTo(1.0);
        assertThat(convertValue(2000000000.0, NANOSECONDS_CUMULATIVE)).isEqualTo(2.0);

        // But other values should stay same.
        assertThat(convertValue(3.0, COUNT)).isEqualTo(3.0);
        assertThat(convertValue(4.0, COUNT_CUMULATIVE)).isEqualTo(4.0);
        assertThat(convertValue(5.0, BYTES)).isEqualTo(5.0);
        assertThat(convertValue(6.0, BYTES_CUMULATIVE)).isEqualTo(6.0);
    }

    @Test
    public void nameConversion() {
        final PrometheusMetricsExporter exporter = new PrometheusMetricsExporter("foo");

        // Unit conversion.
        assertThat(exporter.prometheusName(new MetricKey("bar"), COUNT))
                .isEqualTo("foo_bar");
        assertThat(exporter.prometheusName(new MetricKey("bar"), COUNT_CUMULATIVE))
                .isEqualTo("foo_bar_total");
        assertThat(exporter.prometheusName(new MetricKey("bar"), BYTES))
                .isEqualTo("foo_bar_bytes");
        assertThat(exporter.prometheusName(new MetricKey("bar"), BYTES_CUMULATIVE))
                .isEqualTo("foo_bar_bytes_total");
        assertThat(exporter.prometheusName(new MetricKey("bar"), NANOSECONDS))
                .isEqualTo("foo_bar_seconds");
        assertThat(exporter.prometheusName(new MetricKey("bar"), NANOSECONDS_CUMULATIVE))
                .isEqualTo("foo_bar_seconds_total");

        // Case format conversion.
        assertThat(exporter.prometheusName(new MetricKey("barBaz0"), COUNT))
                .isEqualTo("foo_bar_baz0");
        assertThat(exporter.prometheusName(new MetricKey("bar_Baz"), COUNT))
                .isEqualTo("foo_bar_Baz"); // Conversion is done when a name part is only alphanum.

        // Sanitization.
        assertThat(exporter.prometheusName(new MetricKey("/home"), COUNT))
                .isEqualTo("foo__home"); // '/' has been replaced with '_'.

        // Accept empty name if prefix is not empty.
        assertThat(exporter.prometheusName(new MetricKey(EMPTY, ImmutableMap.of("bar", "baz")), COUNT))
                .isEqualTo("foo");

        // Do not accept empty name if prefix is empty.
        assertThatThrownBy(() -> new PrometheusMetricsExporter().prometheusName(
                new MetricKey(EMPTY, ImmutableMap.of("bar", "baz")), COUNT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void helpSanitization() {
        final CollectorRegistry registry = new CollectorRegistry();
        new PrometheusMetricsExporter(registry).export(new LongAdderGauge(
                new MetricKey("foo"), COUNT, "\r \nbar\tbaz\0"));

        final List<MetricFamilySamples> samples = Collections.list(registry.metricFamilySamples());
        assertThat(samples).hasSize(1);

        final MetricFamilySamples mfs = samples.get(0);
        assertThat(mfs.name).isEqualTo("foo");
        assertThat(mfs.help).isEqualTo("bar baz");
    }

    @Test
    public void labelNameSanitization() {
        final CollectorRegistry registry = new CollectorRegistry();
        new PrometheusMetricsExporter(registry).export(new DefaultHistogram(
                new MetricKey(ImmutableList.of("foo"),
                              ImmutableMap.of("quantile", "bar",
                                              "__reserved", "baz")), COUNT, ""));

        final List<MetricFamilySamples> samples = Collections.list(registry.metricFamilySamples());
        assertThat(samples).hasSize(1);

        final MetricFamilySamples mfs = samples.get(0);
        assertThat(mfs.name).isEqualTo("foo");
        assertThat(mfs.samples.size()).isGreaterThan(0);

        final Sample s = mfs.samples.get(0);
        assertThat(s.labelNames).containsExactly("user___reserved", "user_quantile", "quantile");
        assertThat(s.labelValues).startsWith("baz", "bar");
    }
}
