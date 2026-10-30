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

package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Flags;

import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

class AbstractMetricCollectingBuilderTest {
    @Test
    void testDefaultDistributionStatisticConfig() {
        final AbstractMetricCollectingBuilder abstractMetricCollectingBuilder =
                new AbstractMetricCollectingBuilder(MeterIdPrefixFunction.ofDefault("test")) {};

        assertThat(abstractMetricCollectingBuilder.distributionStatisticConfig())
                .isEqualTo(Flags.distributionStatisticConfig());
    }

    @Test
    void testDistributionStatisticConfigSetter() {
        final AbstractMetricCollectingBuilder abstractMetricCollectingBuilder =
                new AbstractMetricCollectingBuilder(MeterIdPrefixFunction.ofDefault("test")) {};

        final DistributionStatisticConfig distConfig = DistributionStatisticConfig.builder().build();

        abstractMetricCollectingBuilder.distributionStatisticConfig(distConfig);

        assertThat(abstractMetricCollectingBuilder.distributionStatisticConfig())
                .isEqualTo(distConfig);
    }

    @Test
    void testDefaultMaxCardinality() {
        final AbstractMetricCollectingBuilder abstractMetricCollectingBuilder =
                new AbstractMetricCollectingBuilder(MeterIdPrefixFunction.ofDefault("test")) {};

        assertThat(abstractMetricCollectingBuilder.maxCardinality()).isEqualTo(0);
    }

    @Test
    void testMaxCardinalitySetter() {
        final AbstractMetricCollectingBuilder abstractMetricCollectingBuilder =
                new AbstractMetricCollectingBuilder(MeterIdPrefixFunction.ofDefault("test")) {};

        abstractMetricCollectingBuilder.maxCardinality(100);
        assertThat(abstractMetricCollectingBuilder.maxCardinality()).isEqualTo(100);
    }

    @Test
    void testInvalidMaxCardinality() {
        final AbstractMetricCollectingBuilder abstractMetricCollectingBuilder =
                new AbstractMetricCollectingBuilder(MeterIdPrefixFunction.ofDefault("test")) {};

        assertThatThrownBy(() -> abstractMetricCollectingBuilder.maxCardinality(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> abstractMetricCollectingBuilder.maxCardinality(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

