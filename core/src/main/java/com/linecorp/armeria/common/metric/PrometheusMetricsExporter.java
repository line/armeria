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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.common.metric.DefaultHistogram.QUANTILES;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;

import io.prometheus.client.Collector;
import io.prometheus.client.Collector.Describable;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.Collector.Type;
import io.prometheus.client.CollectorRegistry;

/**
 * A {@link MetricsExporter} that exports a {@link Metric} into <a href="https://prometheus.io/">Prometheus</a>
 * {@link CollectorRegistry}.
 *
 * <pre>{@code
 * // Server-side:
 * Server server = ...;
 * server.metrics().addExporter(
 *         new PrometheusMetricsExporter("armeria", "server"));
 *
 * // Client-side:
 * ClientFactory clientFactory = ...;
 * clientFactory.metrics().addExporter(
 *         new PrometheusMetricsExporter("armeria", "client"));
 * }</pre>
 */
public final class PrometheusMetricsExporter extends AbstractMetricsExporter {

    private static final String LABEL_QUANTILE = "quantile";
    private static final double ONE_SECOND_IN_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final Pattern SANITIZE_LABEL_NAME_PREFIX_PATTERN = Pattern.compile("^[^a-zA-Z]+");
    private static final Pattern SANITIZE_LABEL_NAME_BODY_PATTERN = Pattern.compile("[^a-zA-Z0-9_]");
    private static final Pattern SANITIZE_HELP_PATTERN = Pattern.compile("\\p{Cntrl}");
    private static final Pattern ALPHANUM_ONLY_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");
    private static final String RESERVED_LABEL_NAME_PREFIX = "__";

    private final CollectorRegistry registry;
    private final String prefix;

    private final Map<String, CollectorImpl> collectors = new HashMap<>();

    /**
     * Creates a new instance that exports {@link Metrics} to {@link CollectorRegistry#defaultRegistry}.
     *
     * @param prefix the prefix of the exported {@link Metric}s
     */
    public PrometheusMetricsExporter(String... prefix) {
        this(CollectorRegistry.defaultRegistry, prefix);
    }

    /**
     * Creates a new instance.
     *
     * @param registry the {@link CollectorRegistry} where {@link Metric}s are going to be exported to
     * @param prefix the prefix of the exported {@link Metric}s
     */
    public PrometheusMetricsExporter(CollectorRegistry registry, String... prefix) {
        this.registry = requireNonNull(registry, "registry");

        requireNonNull(prefix, "prefix");
        final StringBuilder buf = new StringBuilder();
        for (String p : prefix) {
            requireNonNull(p, "prefix contains null.");
            checkArgument(!p.isEmpty(), "prefix contains an empty string.");
            buf.append(p).append('_');
        }
        this.prefix = Collector.sanitizeMetricName(buf.toString());
    }

    @Override
    protected void exportGauge(Gauge gauge) {
        register(new PrometheusGauge(gauge));
    }

    @Override
    protected void exportDoubleGauge(DoubleGauge gauge) {
        register(new PrometheusDoubleGauge(gauge));
    }

    @Override
    protected void exportHistogram(Histogram histogram) {
        register(new PrometheusHistogram(histogram));
    }

    private void register(PrometheusMetric<?> child) {
        synchronized (collectors) {
            CollectorImpl c = collectors.get(child.name);
            if (c == null) {
                c = new CollectorImpl(child);
                registry.register(c);
                collectors.put(child.name, c);
            } else {
                c.add(child);
            }
        }
    }

    @VisibleForTesting
    String prometheusName(MetricKey key, MetricUnit unit) {
        final StringBuilder buf = new StringBuilder(prefix.length() + 64).append(prefix);

        for (String p : key.nameParts()) {
            if (ALPHANUM_ONLY_PATTERN.matcher(p).matches()) {
                buf.append(CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, p));
            } else {
                buf.append(p);
            }
            buf.append('_');
        }

        if (buf.length() == 0) {
            throw new IllegalArgumentException("can't specify an empty name when prefix is empty");
        }

        switch (unit) {
            case BYTES:
            case BYTES_CUMULATIVE:
                buf.append("bytes");
                break;
            case NANOSECONDS:
            case NANOSECONDS_CUMULATIVE:
                buf.append("seconds");
                break;
            default:
                // Remove the trailing underscore ('_')
                buf.setLength(buf.length() - 1);
        }

        if (unit.isCumulative()) {
            buf.append("_total");
        }

        return Collector.sanitizeMetricName(buf.toString());
    }

    static double convertValue(double value, MetricUnit unit) {
        if (unit == MetricUnit.NANOSECONDS || unit == MetricUnit.NANOSECONDS_CUMULATIVE) {
            return value / ONE_SECOND_IN_NANOS;
        } else {
            return value;
        }
    }

    static String sanitizeLabelName(String name) {
        return SANITIZE_LABEL_NAME_BODY_PATTERN.matcher(
                SANITIZE_LABEL_NAME_PREFIX_PATTERN.matcher(name).replaceFirst("_")).replaceAll("_");
    }

    static String sanitizeHelp(String help) {
        return SANITIZE_HELP_PATTERN.matcher(help.trim()).replaceAll(" ").trim();
    }

    private abstract class PrometheusMetric<T extends Metric> implements Comparable<PrometheusMetric<?>> {

        final T delegate;
        final Type type;
        final String name;
        final String help;
        final List<String> labelNames;
        final List<String> labelValues;

        PrometheusMetric(T delegate) {
            this.delegate = delegate;

            if (delegate instanceof Histogram) {
                type = Type.SUMMARY;
            } else if (delegate.unit().isCumulative()) {
                type = Type.COUNTER;
            } else {
                type = Type.GAUGE;
            }

            name = prometheusName(delegate.key(), delegate.unit());
            help = sanitizeHelp(delegate.description());

            final SortedMap<String, String> labels = new TreeMap<>();
            delegate.key().labels().forEach((k, v) -> {
                String name = k.name();
                if (name.startsWith(RESERVED_LABEL_NAME_PREFIX) ||
                    type == Type.SUMMARY && LABEL_QUANTILE.equals(name)) {
                    name = "user_" + name;
                }
                labels.put(sanitizeLabelName(name), v);
            });

            labelNames = ImmutableList.copyOf(labels.keySet());
            labelValues = ImmutableList.copyOf(labels.values());
        }

        abstract List<Sample> collectSamples();

        @Override
        public int hashCode() {
            return Objects.hash(name, type, labelNames, labelValues);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof PrometheusMetric)) {
                return false;
            }

            final PrometheusMetric<?> that = (PrometheusMetric<?>) obj;
            return name.equals(that.name) &&
                   type == that.type &&
                   labelNames.equals(that.labelNames) &&
                   labelValues.equals(that.labelValues);
        }

        @Override
        public int compareTo(PrometheusMetric<?> that) {
            int res = name.compareTo(that.name);
            if (res != 0) {
                return res;
            }

            res = type.compareTo(that.type);
            if (res != 0) {
                return res;
            }

            final int thisNumLabels = labelNames.size();
            res = Integer.compare(thisNumLabels, that.labelNames.size());
            if (res != 0) {
                return res;
            }

            for (int i = 0; i < thisNumLabels; i++) {
                res = labelNames.get(i).compareTo(that.labelNames.get(i));
                if (res != 0) {
                    return res;
                }
            }

            for (int i = 0; i < thisNumLabels; i++) {
                res = labelValues.get(i).compareTo(that.labelValues.get(i));
                if (res != 0) {
                    return res;
                }
            }

            return 0;
        }
    }

    private final class PrometheusGauge extends PrometheusMetric<Gauge> {

        PrometheusGauge(Gauge delegate) {
            super(delegate);
        }

        @Override
        public List<Sample> collectSamples() {
            return ImmutableList.of(new Sample(
                    name, labelNames, labelValues, convertValue(delegate.value(), delegate.unit())));
        }
    }

    private final class PrometheusDoubleGauge extends PrometheusMetric<DoubleGauge> {

        PrometheusDoubleGauge(DoubleGauge delegate) {
            super(delegate);
        }

        @Override
        public List<Sample> collectSamples() {
            return ImmutableList.of(new Sample(
                    name, labelNames, labelValues, convertValue(delegate.value(), delegate.unit())));
        }
    }

    private final class PrometheusHistogram extends PrometheusMetric<Histogram> {

        PrometheusHistogram(Histogram delegate) {
            super(delegate);
        }

        @Override
        public List<Sample> collectSamples() {
            final ImmutableList.Builder<Sample> builder = ImmutableList.builder();
            final HistogramSnapshot snapshot = delegate.snapshot();
            addQuantileSample(builder, 0.0, snapshot.min());
            for (double q : QUANTILES) {
                addQuantileSample(builder, q, snapshot.value(q));
            }
            addQuantileSample(builder, 1.0, snapshot.max());

            builder.add(new Sample(name + "_count", labelNames, labelValues,
                                   delegate.count())); // No need to convert; just the number of observations
            builder.add(new Sample(name + "_sum", labelNames, labelValues,
                                   convertValue(delegate.sum(), delegate.unit())));
            return builder.build();
        }

        private void addQuantileSample(ImmutableList.Builder<Sample> builder, double quantile, double value) {
            // Append the 'quantile' label.
            final List<String> labelNamesWithQuantile = ImmutableList.<String>builder()
                    .addAll(labelNames).add(LABEL_QUANTILE).build();
            final List<String> labelValuesWithQuantile = ImmutableList.<String>builder()
                    .addAll(labelValues).add(Double.toString(quantile)).build();

            builder.add(new Sample(name, labelNamesWithQuantile, labelValuesWithQuantile,
                                   convertValue(value, delegate.unit())));
        }
    }

    private static final class CollectorImpl extends Collector implements Describable {

        private final String name;
        private final Type type;
        private final String help;
        private final List<MetricFamilySamples> description;
        private final Set<PrometheusMetric<?>> children = new TreeSet<>();

        CollectorImpl(PrometheusMetric<?> firstChild) {
            name = firstChild.name;
            type = firstChild.type;
            help = firstChild.help;
            description = ImmutableList.of(new MetricFamilySamples(name, type, help,
                                                                   ImmutableList.of()));
            add(firstChild);
        }

        void add(PrometheusMetric<?> child) {
            children.add(child);
        }

        @Override
        public List<MetricFamilySamples> describe() {
            return description;
        }

        @Override
        public List<MetricFamilySamples> collect() {
            return ImmutableList.of(new MetricFamilySamples(name, type, help, collectSamples()));
        }

        private List<Sample> collectSamples() {
            return children.stream()
                           .flatMap(m -> m.collectSamples().stream())
                           .collect(toImmutableList());
        }
    }
}
