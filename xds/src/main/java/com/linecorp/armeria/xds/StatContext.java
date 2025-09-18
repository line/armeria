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

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.MeterRegistry;

final class StatContext {

    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix meterIdPrefix;

    StatContext(MeterRegistry meterRegistry, MeterIdPrefix meterIdPrefix) {
        this.meterRegistry = meterRegistry;
        this.meterIdPrefix = meterIdPrefix;
    }

    MeterIdPrefix meterIdPrefix() {
        return meterIdPrefix;
    }

    StatContext withTags(String... tags) {
        return new StatContext(meterRegistry, meterIdPrefix.withTags(tags));
    }

    MeterRegistry meterRegistry() {
        return meterRegistry;
    }
}
