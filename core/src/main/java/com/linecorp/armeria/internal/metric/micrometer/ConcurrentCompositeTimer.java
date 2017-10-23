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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.AbstractTimer;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeter;
import io.micrometer.core.instrument.noop.NoopTimer;
import io.micrometer.core.instrument.stats.hist.Histogram;
import io.micrometer.core.instrument.stats.quantile.Quantiles;

/**
 * A CompositeTimer with less synchronization. See <a
 * href="https://github.com/micrometer-metrics/micrometer/commit/d422fae02e7692a97d055c876744cb9cf42a94a7">
 * patch</a> for detail.
 * TODO(ide) Remove once micrometer-1.0.0-rc3 release.
 */
class ConcurrentCompositeTimer extends AbstractTimer implements CompositeMeter {
    private final Quantiles quantiles;
    private final Histogram histogram;

    private final Map<MeterRegistry, Timer> timers = new ConcurrentHashMap<>();

    ConcurrentCompositeTimer(String name, Iterable<Tag> tags, String description, Quantiles quantiles,
                             Histogram histogram, Clock clock) {
        super(name, tags, description, clock);
        this.quantiles = quantiles;
        this.histogram = histogram;
    }

    @Override
    public void record(long amount, TimeUnit unit) {
        timers.values().forEach(ds -> ds.record(amount, unit));
    }

    @Override
    public long count() {
        return timers.values().stream().findFirst().orElse(NoopTimer.INSTANCE).count();
    }

    @Override
    public double totalTime(TimeUnit unit) {
        return timers.values().stream().findFirst().orElse(NoopTimer.INSTANCE).totalTime(unit);
    }

    @Override
    public void add(MeterRegistry registry) {
        timers.put(registry,
                   registry.timerBuilder(getName()).tags(getTags()).quantiles(quantiles)
                           .histogram(histogram).create());
    }

    @Override
    public void remove(MeterRegistry registry) {
        timers.remove(registry);
    }
}
