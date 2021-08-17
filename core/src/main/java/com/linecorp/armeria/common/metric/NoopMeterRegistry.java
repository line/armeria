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
import java.util.function.ToLongFunction;

import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.Meter.Type;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.noop.NoopCounter;
import io.micrometer.core.instrument.noop.NoopDistributionSummary;
import io.micrometer.core.instrument.noop.NoopFunctionCounter;
import io.micrometer.core.instrument.noop.NoopFunctionTimer;
import io.micrometer.core.instrument.noop.NoopGauge;
import io.micrometer.core.instrument.noop.NoopLongTaskTimer;
import io.micrometer.core.instrument.noop.NoopMeter;
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
        config().namingConvention(NamingConvention.identity);
    }

    @Override
    protected <T> Gauge newGauge(Id id, @Nullable T obj, ToDoubleFunction<T> f) {
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
    protected Timer newTimer(Id id, DistributionStatisticConfig histogramConfig, PauseDetector pauseDetector) {
        return new NoopTimer(id);
    }

    @Override
    protected DistributionSummary newDistributionSummary(Id id, DistributionStatisticConfig distributionConfig,
            double scale) {
        return new NoopDistributionSummary(id);
    }

    @Override
    protected Meter newMeter(Id id, Type type, Iterable<Measurement> measurements) {
        return new NoopMeter(id);
    }

    @Override
    protected <T> FunctionTimer newFunctionTimer(Id id, T obj, ToLongFunction<T> countFunction,
                                                 ToDoubleFunction<T> totalTimeFunction,
                                                 TimeUnit totalTimeFunctionUnits) {
        return new NoopFunctionTimer(id);
    }

    @Override
    protected <T> FunctionCounter newFunctionCounter(Id id, T obj, ToDoubleFunction<T> f) {
        return new NoopFunctionCounter(id);
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.NANOSECONDS;
    }

    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.NONE;
    }
}
