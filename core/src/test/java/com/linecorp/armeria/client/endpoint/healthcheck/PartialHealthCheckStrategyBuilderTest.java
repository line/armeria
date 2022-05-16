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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;

class PartialHealthCheckStrategyBuilderTest {

    private static final double MAX_RATIO = 0.7;

    private static List<Endpoint> createCandidates(int size) {
        return IntStream.range(0, size)
                        .mapToObj(i -> Endpoint.of("dummy" + i))
                        .collect(toImmutableList());
    }

    private PartialHealthCheckStrategyBuilder builder;

    @BeforeEach
    void beforeEach() {
        builder = PartialHealthCheckStrategy.builder();
    }

    @Test
    void maxEndpointCountWhenLessOrEqual0() {
        assertThrows(IllegalArgumentException.class, () -> builder.maxEndpointCount(0));
    }

    @Test
    void maxEndpointCountWhenMaxEndpointRatioAlreadySet() {
        builder.maxEndpointRatio(MAX_RATIO);
        assertThrows(IllegalArgumentException.class, () -> builder.maxEndpointCount(10));
    }

    @Test
    void maxEndpointRatioWhenLessThanEqual0() {
        assertThrows(IllegalArgumentException.class, () -> builder.maxEndpointRatio(0));
    }

    @Test
    void maxEndpointRatioWhenMaxEndpointCountAlreadySet() {
        builder.maxEndpointCount(10);
        assertThrows(IllegalArgumentException.class, () -> builder.maxEndpointRatio(0));
    }

    @Test
    void buildWhenMaximumIsNotSet() {
        assertThrows(IllegalStateException.class, () -> builder.build());
    }

    @Test
    void build() {
        builder.maxEndpointRatio(MAX_RATIO);

        final PartialHealthCheckStrategy actualStrategy = builder.build();
        assertThat(actualStrategy.select(createCandidates(10))).hasSize(7);
        assertThat(actualStrategy.select(createCandidates(20))).hasSize(14);
    }
}
