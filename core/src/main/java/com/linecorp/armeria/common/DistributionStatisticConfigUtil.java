/*
 * Copyright 2024 LINE Corporation
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
 */

package com.linecorp.armeria.common;

import java.time.Duration;

import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

final class DistributionStatisticConfigUtil {
    static final DistributionStatisticConfig DEFAULT_DIST_STAT_CFG =
            DistributionStatisticConfig.builder()
                                       .percentilesHistogram(false)
                                       .serviceLevelObjectives()
                                       .percentiles(0, 0.5, 0.75, 0.9, 0.95, 0.98, 0.99, 0.999, 1.0)
                                       .percentilePrecision(2)
                                       .minimumExpectedValue(1.0)
                                       .maximumExpectedValue(Double.MAX_VALUE)
                                       .expiry(Duration.ofMinutes(3))
                                       .bufferLength(3)
                                       .build();

    private DistributionStatisticConfigUtil() {}
}
