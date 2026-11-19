/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class MaxConnectionAgeConfigTest {

    @Test
    void defaultsToNoReplacement() {
        final MaxConnectionAgeConfig config = MaxConnectionAgeConfig.builder(Duration.ofHours(1)).build();
        assertThat(config.maxConnectionAgeMillis()).isEqualTo(Duration.ofHours(1).toMillis());
        assertThat(config.replacementLeadTimeMillis()).isZero();
    }

    @Test
    void leadTimeUpToTheMaxAgeIsAllowed() {
        final MaxConnectionAgeConfig config = MaxConnectionAgeConfig.builder(1000)
                                                                    .replacementLeadTimeMillis(1000)
                                                                    .build();
        assertThat(config.replacementLeadTimeMillis()).isEqualTo(1000);
    }

    @Test
    void rejectsLeadTimeLongerThanTheMaxAge() {
        assertThatThrownBy(() -> MaxConnectionAgeConfig.builder(1000)
                                                       .replacementLeadTimeMillis(1001)
                                                       .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replacementLeadTimeMillis");
        assertThatThrownBy(() -> MaxConnectionAgeConfig.builder(0)
                                                       .replacementLeadTimeMillis(1)
                                                       .build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> MaxConnectionAgeConfig.builder(999))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaxConnectionAgeConfig.builder(1000).replacementLeadTimeMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setsFactoryOptions() {
        final MaxConnectionAgeConfig config =
                MaxConnectionAgeConfig.builder(Duration.ofMinutes(10))
                                      .replacementLeadTime(Duration.ofSeconds(5))
                                      .build();
        try (ClientFactory factory = ClientFactory.builder().maxConnectionAge(config).build()) {
            assertThat(factory.options().maxConnectionAgeMillis())
                    .isEqualTo(Duration.ofMinutes(10).toMillis());
            assertThat(factory.options().connectionReplacementLeadTimeMillis()).isEqualTo(5000);
        }
    }
}
