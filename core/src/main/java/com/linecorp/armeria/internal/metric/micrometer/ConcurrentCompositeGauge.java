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

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.AbstractMeter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.composite.CompositeMeter;
import io.micrometer.core.instrument.noop.NoopGauge;

/**
 * A CompositeGauge with less synchronization. See <a
 * href="https://github.com/micrometer-metrics/micrometer/commit/d422fae02e7692a97d055c876744cb9cf42a94a7">
 * patch</a> for detail.
 * TODO(ide) Remove once micrometer-1.0.0-rc3 release.
 */
class ConcurrentCompositeGauge<T> extends AbstractMeter implements Gauge, CompositeMeter {
    private final WeakReference<T> ref;
    private final ToDoubleFunction<T> func;

    private final Map<MeterRegistry, Gauge> gauges = new ConcurrentHashMap<>();

    ConcurrentCompositeGauge(String name, Iterable<Tag> tags, String description, T obj,
                             ToDoubleFunction<T> func) {
        super(name, tags, description);
        this.ref = new WeakReference<>(obj);
        this.func = func;
    }

    @Override
    public double value() {
        return gauges.values().stream().findFirst().orElse(NoopGauge.INSTANCE).value();
    }

    @Override
    public void add(MeterRegistry registry) {
        T obj = ref.get();
        if (obj != null) {
            gauges.put(registry, registry.gaugeBuilder(getName(), obj, func).tags(getTags()).create());
        }
    }

    @Override
    public void remove(MeterRegistry registry) {
        gauges.remove(registry);
    }
}
