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
import static java.util.Objects.requireNonNull;

import java.util.SortedMap;
import java.util.TreeMap;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import com.google.common.annotations.VisibleForTesting;

/**
 * A {@link MetricsExporter} that exports a {@link Metric} into
 * <a href="http://metrics.dropwizard.io/">Dropwizard</a> {@link MetricRegistry}.
 *
 * <pre>{@code
 * MetricRegistry metricRegistry = new MetricRegistry();
 *
 * // Server-side:
 * Server server = ...;
 * server.metrics().addExporter(
 *         new DropwizardMetricsExporter(metricRegistry, "armeria", "server"));
 *
 * // Client-side:
 * ClientFactory clientFactory = ...;
 * clientFactory.metrics().addExporter(
 *         new DropwizardMetricsExporter(metricRegistry, "armeria", "client"));
 * }</pre>
 */
public final class DropwizardMetricsExporter extends AbstractMetricsExporter {

    private final MetricRegistry registry;
    private final String prefix;

    /**
     * Creates a new instance.
     *
     * @param registry the {@link MetricRegistry} where {@link Metric}s are going to be exported to
     * @param prefix the prefix of the exported {@link Metric}s
     */
    public DropwizardMetricsExporter(MetricRegistry registry, String... prefix) {
        this.registry = requireNonNull(registry, "registry");

        requireNonNull(prefix, "prefix");
        final StringBuilder buf = new StringBuilder();
        for (String p : prefix) {
            requireNonNull(p, "prefix contains null.");
            checkArgument(!p.isEmpty(), "prefix contains an empty string.");
            buf.append(p).append('.');
        }
        this.prefix = buf.toString();
    }

    @Override
    protected void exportGauge(Gauge gauge) {
        registry.register(dropwizardName(gauge.key()), new DropwizardGauge(gauge));
    }

    @Override
    protected void exportDoubleGauge(DoubleGauge gauge) {
        registry.register(dropwizardName(gauge.key()), new DropwizardDoubleGauge(gauge));
    }

    @Override
    protected void exportHistogram(Histogram histogram) {
        registry.register(dropwizardName(histogram.key()), new DropwizardHistogram(histogram));
    }

    @VisibleForTesting
    String dropwizardName(MetricKey key) {
        final StringBuilder buf = new StringBuilder(prefix.length() + 64).append(prefix);

        for (String p : key.nameParts()) {
            buf.append(p).append('.');
        }

        if (buf.length() == 0) {
            throw new IllegalArgumentException("can't specify an empty name when prefix is empty");
        }

        if (key.labels().isEmpty()) {
            // Remove the trailing dot (.)
            buf.setLength(buf.length() - 1);
        } else {
            // XXX(trustin): Should we make this ('labels') configurable?
            buf.append("labels{");

            final SortedMap<String, String> labels = new TreeMap<>();
            key.labels().forEach((k, v) -> labels.put(k.name(), v));

            labels.forEach((k, v) -> buf.append(k).append('=').append(v).append(','));
            buf.setCharAt(buf.length() - 1, '}');
        }

        return buf.toString();
    }

    private static final class DropwizardGauge implements com.codahale.metrics.Gauge<Long> {

        private final Gauge delegate;

        DropwizardGauge(Gauge delegate) {
            this.delegate = delegate;
        }

        @Override
        public Long getValue() {
            return delegate.value();
        }
    }

    private static final class DropwizardDoubleGauge implements com.codahale.metrics.Gauge<Double> {

        private final DoubleGauge delegate;

        DropwizardDoubleGauge(DoubleGauge delegate) {
            this.delegate = delegate;
        }

        @Override
        public Double getValue() {
            return delegate.value();
        }
    }

    private static final class DropwizardHistogram extends com.codahale.metrics.Histogram {

        private final Histogram delegate;

        DropwizardHistogram(Histogram delegate) {
            super(null);
            this.delegate = delegate;
        }

        @Override
        public void update(int value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(long value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getCount() {
            return delegate.count();
        }

        @Override
        public Snapshot getSnapshot() {
            final HistogramSnapshot s = delegate.snapshot();
            if (s instanceof Snapshot) {
                return (Snapshot) s;
            }

            return new DropwizardHistogramSnapshot() {
                @Override
                public long[] values() {
                    return s.values();
                }

                @Override
                public int size() {
                    return s.size();
                }

                @Override
                public double value(double quantile) {
                    return s.value(quantile);
                }

                @Override
                public double p50() {
                    return s.p50();
                }

                @Override
                public double p75() {
                    return s.p75();
                }

                @Override
                public double p95() {
                    return s.p95();
                }

                @Override
                public double p98() {
                    return s.p98();
                }

                @Override
                public double p99() {
                    return s.p99();
                }

                @Override
                public double p999() {
                    return s.p999();
                }

                @Override
                public long min() {
                    return s.min();
                }

                @Override
                public long max() {
                    return s.max();
                }

                @Override
                public double mean() {
                    return s.mean();
                }

                @Override
                public double stdDev() {
                    return s.stdDev();
                }
            };
        }
    }
}
