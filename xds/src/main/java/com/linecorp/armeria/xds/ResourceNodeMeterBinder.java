/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
 */

package com.linecorp.armeria.xds;

import java.util.Locale;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Records metrics for each {@link ResourceNode}.
 * This is not done at the user-exposed {@link SnapshotWatcher} level so that users can
 * observe the internal state/lifecycle of {@link ResourceNode}s via metrics.
 */
final class ResourceNodeMeterBinder implements ResourceWatcher<XdsResource> {

    private final MeterRegistry meterRegistry;
    private long updatedRevision;
    private boolean closed;

    private final Gauge revisionGauge;
    private final Counter errorCounter;
    private final Counter missingCounter;

    ResourceNodeMeterBinder(MeterRegistry meterRegistry,
                            MeterIdPrefix meterIdPrefix, XdsType type, String resourceName) {
        this.meterRegistry = meterRegistry;
        meterIdPrefix = meterIdPrefix.withTags("name", resourceName,
                                               "type", type.name().toLowerCase(Locale.ROOT));
        revisionGauge = Gauge.builder(meterIdPrefix.name("resource.node.revision"), () -> updatedRevision)
                             .tags(meterIdPrefix.tags())
                             .register(meterRegistry);
        errorCounter = Counter.builder(meterIdPrefix.name("resource.node.error"))
                              .tags(meterIdPrefix.tags())
                              .register(meterRegistry);
        missingCounter = Counter.builder(meterIdPrefix.name("resource.node.missing"))
                                .tags(meterIdPrefix.tags())
                                .register(meterRegistry);
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        meterRegistry.remove(revisionGauge);
        meterRegistry.remove(errorCounter);
        meterRegistry.remove(missingCounter);
    }

    @Override
    public void onError(XdsType type, String resourceName, Status status) {
        errorCounter.increment();
    }

    @Override
    public void onResourceDoesNotExist(XdsType type, String resourceName) {
        missingCounter.increment();
    }

    @Override
    public void onChanged(XdsResource update) {
        updatedRevision = update.revision();
    }
}
