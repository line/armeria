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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.composite.CompositeMeter;
import io.micrometer.core.instrument.noop.NoopCounter;

/**
 * A CompositeCounter with less synchronization. See <a
 * href="https://github.com/micrometer-metrics/micrometer/commit/d422fae02e7692a97d055c876744cb9cf42a94a7">
 * patch</a> for detail.
 * TODO(ide) Remove once micrometer-1.0.0-rc3 release.
 */
class ConcurrentCompositeCounter extends AbstractMeter implements Counter, CompositeMeter {
    private final Map<MeterRegistry, Counter> counters = new ConcurrentHashMap<>();

    ConcurrentCompositeCounter(String name, Iterable<Tag> tags, String description) {
        super(name, tags, description);
    }

    @Override
    public void increment(double amount) {
        counters.values().forEach(Counter::increment);
    }

    @Override
    public double count() {
        return counters.values().stream().findFirst().orElse(NoopCounter.INSTANCE).count();
    }

    @Override
    public void add(MeterRegistry registry) {
        counters.put(registry, registry.counter(getName(), getTags()));
    }

    @Override
    public void remove(MeterRegistry registry) {
        counters.remove(registry);
    }
}
