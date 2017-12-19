/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.metric;

import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.Meter.Type;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.histogram.HistogramConfig;
import io.micrometer.core.instrument.noop.NoopCounter;
import io.micrometer.core.instrument.noop.NoopDistributionSummary;
import io.micrometer.core.instrument.noop.NoopGauge;
import io.micrometer.core.instrument.noop.NoopLongTaskTimer;
import io.micrometer.core.instrument.noop.NoopTimer;

/**
 * A {@link MeterRegistry} which does not record any values.
 */
public final class NoopMeterRegistry extends MeterRegistry {

    private static final NoopMeterRegistry INSTANCE = new NoopMeterRegistry();

    /**
     * Returns the singleton instance.
     */
    public static NoopMeterRegistry get() {
        return INSTANCE;
    }

    private NoopMeterRegistry() {
        super(Clock.SYSTEM);
        config().namingConvention(MoreNamingConventions.identity());
    }

    @Override
    protected <T> Gauge newGauge(Id id, T obj, ToDoubleFunction<T> f) {
        return new NoopGauge(id);
    }

    @Override
    protected Counter newCounter(Id id) {
        return new NoopCounter(id);
    }

    @Override
    protected LongTaskTimer newLongTaskTimer(Id id) {
        return new NoopLongTaskTimer(id);
    }

    @Override
    protected Timer newTimer(Id id, HistogramConfig histogramConfig) {
        return new NoopTimer(id);
    }

    @Override
    protected DistributionSummary newDistributionSummary(Id id, HistogramConfig histogramConfig) {
        return new NoopDistributionSummary(id);
    }

    @Override
    protected void newMeter(Id id, Type type, Iterable<Measurement> measurements) {}

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.NANOSECONDS;
    }
}
