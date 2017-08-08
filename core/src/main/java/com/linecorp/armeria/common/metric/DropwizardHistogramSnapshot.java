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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.function.Function;

import com.codahale.metrics.Snapshot;
import com.github.rollingmetrics.histogram.accumulator.Accumulator;
import com.google.common.base.MoreObjects;

/**
 * A {@link HistogramSnapshot} which is used to satisfy the type constraint of
 * {@link Accumulator#getSnapshot(Function)}.
 */
abstract class DropwizardHistogramSnapshot extends Snapshot implements HistogramSnapshot {

    @Override
    public final double getValue(double quantile) {
        return value(quantile);
    }

    @Override
    public final long[] getValues() {
        return values();
    }

    @Override
    public final long getMax() {
        return max();
    }

    @Override
    public final double getMean() {
        return mean();
    }

    @Override
    public final long getMin() {
        return min();
    }

    @Override
    public final double getStdDev() {
        return stdDev();
    }

    @Override
    public final void dump(OutputStream output) {
        final PrintWriter p = new PrintWriter(new OutputStreamWriter(output, UTF_8));
        for (long value : values()) {
            p.println(value);
        }
    }

    @Override
    public final String toString() {
        return MoreObjects.toStringHelper("")
                          .add("min", min())
                          .add("mean", mean())
                          .add("max", max())
                          .add("stdDev", stdDev())
                          .add("p50", p50())
                          .add("p75", p75())
                          .add("p95", p95())
                          .add("p98", p98())
                          .add("p99", p99())
                          .add("p999", p999())
                          .toString();
    }
}
