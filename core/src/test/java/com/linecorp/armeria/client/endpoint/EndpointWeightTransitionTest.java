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

package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.Endpoint;

class EndpointWeightTransitionTest {

    @ParameterizedTest
    @ValueSource(doubles = { 0.1, Double.MIN_VALUE, 1, 100, Double.MAX_VALUE })
    void aggressionBoundaries(double aggression) {
        final Endpoint endpoint = Endpoint.of("foo.com").withWeight(100);
        for (int i = 1; i <= 10; i++) {
            final int weight = EndpointWeightTransition.aggression(aggression, 0.0)
                                                       .compute(endpoint, i, 10);
            assertThat(weight).isBetween(0, 100);
        }
    }

    @Test
    void minWeight() {
        final Endpoint endpoint = Endpoint.of("foo.com").withWeight(100);
        final EndpointWeightTransition weightTransition = EndpointWeightTransition.aggression(1, 0.5);
        for (int i = 0; i <= 5; i++) {
            assertThat(weightTransition.compute(endpoint, i, 10)).isEqualTo(50);
        }
        for (int i = 6; i <= 10; i++) {
            assertThat(weightTransition.compute(endpoint, i, 10)).isEqualTo(i * 10);
        }
    }

    @Test
    void invalidParameters() {
        assertThatThrownBy(() -> EndpointWeightTransition.aggression(0, 0.5));
        assertThatThrownBy(() -> EndpointWeightTransition.aggression(-1, 0.5));
        assertThatThrownBy(() -> EndpointWeightTransition.aggression(0.1, 1.2));
        assertThatThrownBy(() -> EndpointWeightTransition.aggression(0.1, -1.2));
        assertThatThrownBy(() -> EndpointWeightTransition.aggression(Double.NaN, 0.5));
        assertThatThrownBy(() -> EndpointWeightTransition.aggression(0.5, Double.NaN));
    }
}
