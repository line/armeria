/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;

public class HealthCheckedEndpointGroupBuilderTest {
    private static final String PATH = "testPath";

    private EndpointGroup delegate;

    @BeforeEach
    void beforeEach() {
        delegate = EndpointGroup.of(Endpoint.of("endpoint1"));
    }

    @Test
    void defaultHealthCheckStrategy() {
        final HealthCheckedEndpointGroup group1 = new HealthCheckedEndpointGroupBuilder(delegate, PATH).build();
        final HealthCheckedEndpointGroup group2 = new HealthCheckedEndpointGroupBuilder(delegate, PATH).build();
        assertThat(group1.healthCheckStrategy).isSameAs(HealthCheckStrategy.all());
    }

    @Test
    void partialHealthCheckStrategyMutuallyExclusive() {
        // max count
        final HealthCheckedEndpointGroupBuilder maxCntBuilder =
                new HealthCheckedEndpointGroupBuilder(delegate, PATH).maxEndpointCount(10);

        assertThatThrownBy(() -> maxCntBuilder.maxEndpointRatio(0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Maximum endpoint count is already set.");

        assertThat(maxCntBuilder.build().healthCheckStrategy).isInstanceOf(PartialHealthCheckStrategy.class);

        // max ratio
        final HealthCheckedEndpointGroupBuilder maxRatioBuilder =
                new HealthCheckedEndpointGroupBuilder(delegate, PATH).maxEndpointRatio(0.5);

        assertThatThrownBy(() -> maxRatioBuilder.maxEndpointCount(10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Maximum endpoint ratio is already set.");

        assertThat(maxRatioBuilder.build().healthCheckStrategy).isInstanceOf(PartialHealthCheckStrategy.class);

        // all health check by ratio 1.0
        final HealthCheckedEndpointGroupBuilder allHealthCheckBuilder1 =
                new HealthCheckedEndpointGroupBuilder(delegate, PATH).maxEndpointRatio(1.0);

        assertThatThrownBy(() -> allHealthCheckBuilder1.maxEndpointCount(10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Maximum endpoint ratio is already set.");

        assertThat(allHealthCheckBuilder1.build().healthCheckStrategy)
                .isSameAs(HealthCheckStrategy.all());

        // all health check by default
        final HealthCheckedEndpointGroupBuilder allHealthCheckBuilder2 =
                new HealthCheckedEndpointGroupBuilder(delegate, PATH);

        assertThat(allHealthCheckBuilder2.build().healthCheckStrategy)
                .isSameAs(HealthCheckStrategy.all());
    }
}
