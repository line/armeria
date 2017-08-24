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

import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.AbstractMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter.Type;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.noop.NoopCounter;
import io.micrometer.core.instrument.noop.NoopDistributionSummary;
import io.micrometer.core.instrument.noop.NoopGauge;
import io.micrometer.core.instrument.noop.NoopLongTaskTimer;
import io.micrometer.core.instrument.noop.NoopTimer;
import io.micrometer.core.instrument.stats.hist.Histogram;
import io.micrometer.core.instrument.stats.quantile.Quantiles;

/**
 * A {@link MeterRegistry} which does not record any values.
 */
public final class NoopMeterRegistry extends AbstractMeterRegistry {

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
    protected DistributionSummary newDistributionSummary(String name, Iterable<Tag> tags, String description,
                                                         Quantiles quantiles, Histogram<?> histogram) {
        return NoopDistributionSummary.INSTANCE;
    }

    @Override
    protected <T> Gauge newGauge(String name, Iterable<Tag> tags, String description, ToDoubleFunction<T> f,
                                 T obj) {
        return NoopGauge.INSTANCE;
    }

    @Override
    protected Counter newCounter(String name, Iterable<Tag> tags, String description) {
        return NoopCounter.INSTANCE;
    }

    @Override
    protected LongTaskTimer newLongTaskTimer(String name, Iterable<Tag> tags, String description) {
        return NoopLongTaskTimer.INSTANCE;
    }

    @Override
    protected Timer newTimer(String name, Iterable<Tag> tags, String description, Histogram<?> histogram,
                             Quantiles quantiles) {
        return NoopTimer.INSTANCE;
    }

    @Override
    protected void newMeter(String name, Iterable<Tag> tags, Type type, Iterable<Measurement> measurements) {}
}
