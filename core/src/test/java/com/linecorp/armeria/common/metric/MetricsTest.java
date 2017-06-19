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
import static com.linecorp.armeria.common.metric.MetricUnit.COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.testing.internal.AnticipatedException;

public class MetricsTest {

    @Test
    public void exporterLifeCycle() {
        final MetricsExporter exporter = mock(MetricsExporter.class);
        final Metrics metrics = new Metrics();

        // Make sure a previously registered metric is exported.
        final Metric a = new LongAdderGauge(new MetricKey("a"), COUNT, "");
        metrics.register(a);
        metrics.addExporter(exporter);
        verify(exporter, times(1)).export(a);

        // A newly registered metric should be exported as well.
        final Metric b = new LongAdderGauge(new MetricKey("b"), COUNT, "");
        metrics.register(b);
        verify(exporter, times(1)).export(b);

        // Attempting to add the same exporter again should not trigger double exports.
        reset(exporter);
        metrics.addExporter(exporter);
        verify(exporter, never()).export(any());
        final Metric c = new LongAdderGauge(new MetricKey("c"), COUNT, "");
        metrics.register(c);
        verify(exporter, times(1)).export(c);

        // Once an exporter is removed, it should not be invoked at all.
        reset(exporter);
        metrics.removeExporter(exporter);
        final Metric d = new LongAdderGauge(new MetricKey("d"), COUNT, "");
        metrics.register(d);
        verify(exporter, never()).export(any());

        // Attempting to remove an exporter which doesn't exist should be fine.
        metrics.removeExporter(exporter);
    }

    @Test
    public void badExporter() {
        final Metrics metrics = new Metrics();
        final MetricsExporter exporter = mock(MetricsExporter.class);
        // Add a bad exporter first.
        metrics.addExporter(metric -> {
            throw new AnticipatedException();
        });
        // and then a mock.
        metrics.addExporter(exporter);

        // All exporters should be invoked even if a bad exporter throws an exception.
        final Metric a = new LongAdderGauge(new MetricKey("a"), COUNT, "");
        metrics.register(a);
        verify(exporter, times(1)).export(a);
    }

    @Test
    public void metricDoubleRegistration() {
        final Metrics metrics = new Metrics();
        final Gauge gauge = new LongAdderGauge(new MetricKey("a"), COUNT, "");

        // Attempting to register the same metric twice is OK as long as they are same instances.
        assertThat(metrics.register(gauge)).isSameAs(gauge);
        assertThat(metrics.register(gauge)).isSameAs(gauge);

        // However, it's not allowed to register a different instance.
        assertThatThrownBy(() -> metrics.register(new LongAdderGauge(new MetricKey("a"), COUNT, "")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void getMetric() {
        final Metrics metrics = new Metrics();
        final Metric a = new LongAdderGauge(new MetricKey("a"), COUNT, "");
        metrics.register(a);

        // Key and type match.
        assertThat(metrics.metric(new MetricKey("a"), Gauge.class)).isSameAs(a);

        // Key does not match.
        assertThat(metrics.metric(new MetricKey("b"), Gauge.class)).isNull();

        // Key matches, but type doesn't.
        assertThatThrownBy(() -> metrics.metric(new MetricKey("a"), Histogram.class))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void getOrCreateMetric() {
        final Metrics metrics = new Metrics();
        final LongAdderGauge a = metrics.metric(new MetricKey("a"), COUNT, "",
                                                LongAdderGauge.class, LongAdderGauge::new);

        assertThat(metrics.metric(new MetricKey("a"), COUNT, "",
                                  LongAdderGauge.class, LongAdderGauge::new)).isSameAs(a);

        // Type mismatches.
        assertThatThrownBy(() -> metrics.metric(new MetricKey("a"), COUNT, "",
                                                SupplierGauge.class,
                                                (k, u, d) -> new SupplierGauge(k, u, d, () -> 0)))
                .isInstanceOf(IllegalStateException.class);

        // Unit mismatches.
        assertThatThrownBy(() -> metrics.metric(new MetricKey("a"), BYTES, "",
                                                LongAdderGauge.class, LongAdderGauge::new))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void getOrCreateMetricWithBadFactory() {
        final Metrics metrics = new Metrics();

        // Factory threw an exception.
        assertThatThrownBy(() -> metrics.metric(
                new MetricKey("a"), COUNT, "", LongAdderGauge.class,
                (k, u, d) -> {
                    throw new AnticipatedException();
                })).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(AnticipatedException.class);

        // Factory returned a metric with mismatching key.
        assertThatThrownBy(() -> metrics.metric(new MetricKey("a"), COUNT, "",
                                                LongAdderGauge.class,
                                                (k, u, d) -> new LongAdderGauge(new MetricKey("b"), u, d)))
                .isInstanceOf(IllegalStateException.class);

        // Factory returned a metric with mismatching unit.
        assertThatThrownBy(() -> metrics.metric(new MetricKey("a"), COUNT, "",
                                                LongAdderGauge.class,
                                                (k, u, d) -> new LongAdderGauge(k, BYTES, d)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void searchMetrics() {
        final Metrics metrics = new Metrics();
        final Metric a = metrics.register(new LongAdderGauge(
                new MetricKey(ImmutableList.of("a", "b"),
                              ImmutableMap.of("foo", "1")), COUNT, ""));
        final Metric b = metrics.register(new LongAdderGauge(
                new MetricKey(ImmutableList.of("a", "c"),
                              ImmutableMap.of("foo", "1", "bar", "2")), COUNT, ""));

        assertThat(metrics.metrics()).containsExactlyInAnyOrder(a, b);
        assertThat(metrics.metrics(new MetricKey("a"))).containsExactlyInAnyOrder(a, b);
        assertThat(metrics.metrics(new MetricKey("a", "b"))).containsExactly(a);
        assertThat(metrics.metrics(new MetricKey(ImmutableList.of("a"),
                                                 ImmutableMap.of("foo", "1")))).containsExactlyInAnyOrder(a, b);
        assertThat(metrics.metrics(new MetricKey(ImmutableList.of("a"),
                                                 ImmutableMap.of("bar", "2")))).containsExactly(b);
    }

    @Test
    public void groupDoubleRegistration() {
        final Metrics metrics = new Metrics();
        final MyMetricGroup group = new MyMetricGroup(new MetricKey("a"));

        // Attempting to register the same group twice is OK as long as they are same instances.
        assertThat(metrics.register(group)).isSameAs(group);
        assertThat(metrics.register(group)).isSameAs(group);

        // However, it's not allowed to register a different instance.
        assertThatThrownBy(() -> metrics.register(new MyMetricGroup(new MetricKey("a"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void getGroup() {
        final Metrics metrics = new Metrics();
        final MetricGroup a = new MyMetricGroup(new MetricKey("a"));
        metrics.register(a);

        // Key and type match.
        assertThat(metrics.group(new MetricKey("a"), MyMetricGroup.class)).isSameAs(a);

        // Key does not match.
        assertThat(metrics.group(new MetricKey("b"), MyMetricGroup.class)).isNull();

        // Key matches, but type doesn't.
        assertThatThrownBy(() -> metrics.group(new MetricKey("a"), RequestMetrics.class))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void getOrCreateGroup() {
        final Metrics metrics = new Metrics();
        final MyMetricGroup a = metrics.group(new MetricKey("a"), MyMetricGroup.class,
                                              (parent, key) -> new MyMetricGroup(key));

        assertThat(metrics.group(new MetricKey("a"), MyMetricGroup.class,
                                 (parent, key) -> new MyMetricGroup(key))).isSameAs(a);

        // Type mismatches.
        assertThatThrownBy(() -> metrics.group(new MetricKey("a"), RequestMetrics.class,
                                               (parent, key) -> mock(RequestMetrics.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void searchGroups() {
        final Metrics metrics = new Metrics();
        final MyMetricGroup a = metrics.register(new MyMetricGroup(
                new MetricKey(ImmutableList.of("a", "b"),
                              ImmutableMap.of("foo", "1"))));
        final MyMetricGroup b = metrics.register(new MyMetricGroup(
                new MetricKey(ImmutableList.of("a", "c"),
                              ImmutableMap.of("foo", "1", "bar", "2"))));

        assertThat(metrics.groups()).containsExactlyInAnyOrder(a, b);
        assertThat(metrics.groups(MyMetricGroup.class)).containsExactlyInAnyOrder(a, b);
        assertThat(metrics.groups(RequestMetrics.class)).isEmpty();
        assertThat(metrics.groups(new MetricKey("a"), MyMetricGroup.class)).containsExactlyInAnyOrder(a, b);
        assertThat(metrics.groups(new MetricKey("a", "b"), MyMetricGroup.class)).containsExactly(a);
        assertThat(metrics.groups(new MetricKey(ImmutableList.of("a"),
                                                ImmutableMap.of("foo", "1")),
                                  MyMetricGroup.class)).containsExactlyInAnyOrder(a, b);
        assertThat(metrics.groups(new MetricKey(ImmutableList.of("a"),
                                                ImmutableMap.of("bar", "2")),
                                  MyMetricGroup.class)).containsExactly(b);
    }

    private static final class MyMetricGroup implements MetricGroup {

        private final MetricKey key;

        MyMetricGroup(MetricKey key) {
            this.key = key;
        }

        @Override
        public MetricKey key() {
            return key;
        }
    }
}
