/*
 * Copyright 2017 LINE Corporation
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
 *
 * Copyright 2017 Pivotal Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.internal.metric.micrometer;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.AbstractMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeter;
import io.micrometer.core.instrument.stats.hist.Histogram;
import io.micrometer.core.instrument.stats.quantile.Quantiles;

/**
 * The clock of the composite effectively overrides the clocks of the registries it manages without actually
 * replacing the state of the clock in these registries with the exception of long task timers, whose clock
 * cannot be overridden.
 *
 * @author Jon Schneider
 *
 * <p>
 * A CompositeMeterRegistry with less synchronization. See <a
 * href="https://github.com/micrometer-metrics/micrometer/commit/d422fae02e7692a97d055c876744cb9cf42a94a7">
 * patch</a> for detail.
 * TODO(ide) Remove once micrometer-1.0.0-rc3 release.
 * </p>
 */
public class ConcurrentCompositeMeterRegistry extends AbstractMeterRegistry {
    private final Set<MeterRegistry> registries = ConcurrentHashMap.newKeySet();
    private final Collection<CompositeMeter> compositeMeters = new CopyOnWriteArrayList<>();

    public ConcurrentCompositeMeterRegistry() {
        this(Clock.SYSTEM);
    }

    public ConcurrentCompositeMeterRegistry(Clock clock) {
        super(clock);
    }

    @Override
    protected Timer newTimer(String name, Iterable<Tag> tags, String description, Histogram<?> histogram,
                             Quantiles quantiles) {
        ConcurrentCompositeTimer timer = new ConcurrentCompositeTimer(name, tags, description, quantiles,
                                                                      histogram, clock);
        compositeMeters.add(timer);
        registries.forEach(timer::add);
        return timer;
    }

    @Override
    protected DistributionSummary newDistributionSummary(String name, Iterable<Tag> tags, String description,
                                                         Quantiles quantiles, Histogram<?> histogram) {
        ConcurrentCompositeDistributionSummary ds = new ConcurrentCompositeDistributionSummary(
                name, tags, description, quantiles, histogram);
        compositeMeters.add(ds);
        registries.forEach(ds::add);
        return ds;
    }

    @Override
    protected Counter newCounter(String name, Iterable<Tag> tags, String description) {
        ConcurrentCompositeCounter counter = new ConcurrentCompositeCounter(name, tags, description);
        compositeMeters.add(counter);
        registries.forEach(counter::add);
        return counter;
    }

    @Override
    protected LongTaskTimer newLongTaskTimer(String name, Iterable<Tag> tags, String description) {
        ConcurrentCompositeLongTaskTimer longTaskTimer = new ConcurrentCompositeLongTaskTimer(name, tags,
                                                                                              description);
        compositeMeters.add(longTaskTimer);
        registries.forEach(longTaskTimer::add);
        return longTaskTimer;
    }

    @Override
    protected <T> Gauge newGauge(String name, Iterable<Tag> tags, String description, ToDoubleFunction<T> f,
                                 T obj) {
        ConcurrentCompositeGauge<T> gauge = new ConcurrentCompositeGauge<>(name, tags, description, obj, f);
        compositeMeters.add(gauge);
        registries.forEach(gauge::add);
        return gauge;
    }

    @Override
    protected void newMeter(String name, Iterable<Tag> tags, Meter.Type type,
                            Iterable<Measurement> measurements) {
        CompositeMeter meter = new CompositeCustomMeter(name, tags, type, measurements);
        compositeMeters.add(meter);
        registries.forEach(meter::add);
    }

    public void add(MeterRegistry registry) {
        if (registries.add(registry)) {
            compositeMeters.forEach(m -> m.add(registry));
        }
    }

    public void remove(MeterRegistry registry) {
        if (registries.remove(registry)) {
            compositeMeters.forEach(m -> m.remove(registry));
        }
    }
}
