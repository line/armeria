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
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.composite.CompositeMeter;
import io.micrometer.core.instrument.noop.NoopDistributionSummary;
import io.micrometer.core.instrument.stats.hist.Histogram;
import io.micrometer.core.instrument.stats.quantile.Quantiles;

/**
 * A CompositeDistributionSummary with less synchronization. See <a
 * href="https://github.com/micrometer-metrics/micrometer/commit/d422fae02e7692a97d055c876744cb9cf42a94a7">
 * patch</a> for detail.
 * TODO(ide) Remove once micrometer-1.0.0-rc3 release.
 */
class ConcurrentCompositeDistributionSummary extends AbstractMeter
        implements DistributionSummary, CompositeMeter {
    private final Quantiles quantiles;
    private final Histogram histogram;

    private final Map<MeterRegistry, DistributionSummary> distributionSummaries = new ConcurrentHashMap<>();

    ConcurrentCompositeDistributionSummary(String name, Iterable<Tag> tags, String description,
                                           Quantiles quantiles, Histogram histogram) {
        super(name, tags, description);
        this.quantiles = quantiles;
        this.histogram = histogram;
    }

    @Override
    public void record(double amount) {
        distributionSummaries.values().forEach(ds -> ds.record(amount));
    }

    @Override
    public long count() {
        return distributionSummaries.values().stream().findFirst().orElse(NoopDistributionSummary.INSTANCE)
                                    .count();
    }

    @Override
    public double totalAmount() {
        return distributionSummaries.values().stream().findFirst().orElse(NoopDistributionSummary.INSTANCE)
                                    .totalAmount();
    }

    @Override
    public void add(MeterRegistry registry) {
        distributionSummaries.put(registry,
                                  registry.summaryBuilder(getName()).tags(getTags()).quantiles(quantiles)
                                          .histogram(histogram).create());
    }

    @Override
    public void remove(MeterRegistry registry) {
        distributionSummaries.remove(registry);
    }
}
