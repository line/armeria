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

import io.micrometer.core.instrument.AbstractMeter;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.composite.CompositeMeter;
import io.micrometer.core.instrument.noop.NoopLongTaskTimer;

/**
 * A CompositeLongTaskTimer with less synchronization. See <a
 * href="https://github.com/micrometer-metrics/micrometer/commit/d422fae02e7692a97d055c876744cb9cf42a94a7">
 * patch</a> for detail.
 * TODO(ide) Remove once micrometer-1.0.0-rc3 release.
 */
class ConcurrentCompositeLongTaskTimer extends AbstractMeter implements LongTaskTimer, CompositeMeter {
    private final Map<MeterRegistry, LongTaskTimer> timers = new ConcurrentHashMap<>();

    ConcurrentCompositeLongTaskTimer(String name, Iterable<Tag> tags, String description) {
        super(name, tags, description);
    }

    @Override
    public long start() {
        return timers.values().stream()
                     .map(LongTaskTimer::start)
                     .reduce((t1, t2) -> t2)
                     .orElse(NoopLongTaskTimer.INSTANCE.start());
    }

    @Override
    public long stop(long task) {
        return timers.values().stream()
                     .map(ltt -> ltt.stop(task))
                     .reduce((t1, t2) -> t2 == -1 ? t1 : t2)
                     .orElse(NoopLongTaskTimer.INSTANCE.stop(task));
    }

    @Override
    public long duration(long task) {
        return timers.values().stream()
                     .map(ltt -> ltt.duration(task))
                     .reduce((t1, t2) -> t2 == -1 ? t1 : t2)
                     .orElse(NoopLongTaskTimer.INSTANCE.duration(task));
    }

    @Override
    public long duration() {
        return timers.values().stream()
                     .map(LongTaskTimer::duration)
                     .reduce((t1, t2) -> t2)
                     .orElse(NoopLongTaskTimer.INSTANCE.duration());
    }

    @Override
    public int activeTasks() {
        return timers.values().stream()
                     .map(LongTaskTimer::activeTasks)
                     .reduce((t1, t2) -> t2)
                     .orElse(NoopLongTaskTimer.INSTANCE.activeTasks());
    }

    @Override
    public void add(MeterRegistry registry) {
        timers.put(registry, registry.more().longTaskTimer(getName(), getTags()));
    }

    @Override
    public void remove(MeterRegistry registry) {
        timers.remove(registry);
    }
}
