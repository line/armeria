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

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;

public class AllHealthCheckStrategyTest {

    private static List<Endpoint> createCandidates(int size) {
        final Random random = new Random();

        return IntStream.range(0, size)
                        .mapToObj(i -> Endpoint.of("dummy" + random.nextInt()))
                        .collect(Collectors.toList());
    }

    @Test
    void shouldReturnAllCandidates() {
        final List<Endpoint> candidates = createCandidates(10);
        assertThat(HealthCheckStrategy.all().select(candidates)).isEqualTo(candidates);
    }
}
