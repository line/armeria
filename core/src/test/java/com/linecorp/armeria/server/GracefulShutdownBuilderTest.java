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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.GracefulShutdown;
import com.linecorp.armeria.common.ShuttingDownException;
import com.linecorp.armeria.internal.testing.AnticipatedException;

class GracefulShutdownBuilderTest {

    @Test
    void testInvalidValues() {
        assertThatThrownBy(() -> GracefulShutdown.builder().quietPeriodMillis(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("quietPeriod:.*?\\(expected: >= 0\\)");

        assertThatThrownBy(() -> GracefulShutdown.builder().timeoutMillis(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("timeout:.*?\\(expected: >= 0\\)");

        assertThatThrownBy(() -> {
            GracefulShutdown.builder()
                            .quietPeriodMillis(10)
                            .timeoutMillis(5)
                            .build();
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching("timeout must be greater than or equal to quietPeriod");
    }

    @Test
    void testDefault() {
        final GracefulShutdown gracefulShutdown = GracefulShutdown.builder().build();
        assertThat(gracefulShutdown.quietPeriod()).isZero();
        assertThat(gracefulShutdown.timeout()).isZero();
    }

    @Test
    void testCustomValues() {
        final GracefulShutdown gracefulShutdown =
                GracefulShutdown.builder()
                                .quietPeriod(Duration.ofSeconds(1))
                                .timeout(Duration.ofSeconds(2))
                                .build();
        assertThat(gracefulShutdown.quietPeriod()).isEqualTo(Duration.ofSeconds(1));
        assertThat(gracefulShutdown.timeout()).isEqualTo(Duration.ofSeconds(2));
    }
}
