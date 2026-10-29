/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.internal.common.KeepAliveHandlerUtil.jitteredMaxConnectionAgeNanos;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class KeepAliveHandlerUtilTest {

    private static final int NUM_DRAWS = 10_000;

    @ParameterizedTest
    @CsvSource({
            "10000, 0.01, 9900",
            "10000, 0.5,  5000",
            "10000, 1.0,  1000",
            "2000,  0.01, 1980",
            "2000,  0.5,  1000",
            "2000,  1.0,  1000",
    })
    void jitteredMaxConnectionAgeIsSpreadBelowMax(long maxConnectionAgeMillis, double jitterRate,
                                                  long expectedLowerBoundMillis) {
        final long maxConnectionAgeNanos = TimeUnit.MILLISECONDS.toNanos(maxConnectionAgeMillis);
        final long expectedLowerBoundNanos = TimeUnit.MILLISECONDS.toNanos(expectedLowerBoundMillis);
        final Set<Long> distinctAges = new HashSet<>();
        for (int i = 0; i < NUM_DRAWS; i++) {
            final long ageNanos = jitteredMaxConnectionAgeNanos(maxConnectionAgeNanos, jitterRate);
            assertThat(ageNanos)
                    .as("effective age for maxConnectionAge=%sms, jitterRate=%s",
                        maxConnectionAgeMillis, jitterRate)
                    .isGreaterThanOrEqualTo(expectedLowerBoundNanos)
                    .isLessThan(maxConnectionAgeNanos);
            distinctAges.add(ageNanos);
        }
        assertThat(distinctAges).as("distinct effective ages over %s draws", NUM_DRAWS)
                                .hasSizeGreaterThan(1);
    }

    @ParameterizedTest
    @CsvSource({
            "500,  0.01",
            "500,  1.0",
            "1000, 0.01",
            "1000, 1.0",
    })
    void maxConnectionAgeNotAboveOneSecondIsNotJittered(long maxConnectionAgeMillis, double jitterRate) {
        final long maxConnectionAgeNanos = TimeUnit.MILLISECONDS.toNanos(maxConnectionAgeMillis);
        for (int i = 0; i < NUM_DRAWS; i++) {
            assertThat(jitteredMaxConnectionAgeNanos(maxConnectionAgeNanos, jitterRate))
                    .isEqualTo(maxConnectionAgeNanos);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 50, 100 })
    void jitteredUnboundedMaxConnectionAgeDoesNotOverflow(int jitterPercent) {
        final long expectedLowerBoundNanos = Math.max(TimeUnit.SECONDS.toNanos(1),
                                                      Long.MAX_VALUE / 100 * (100 - jitterPercent));
        for (int i = 0; i < NUM_DRAWS; i++) {
            assertThat(jitteredMaxConnectionAgeNanos(Long.MAX_VALUE, jitterPercent / 100.0))
                    .isGreaterThanOrEqualTo(expectedLowerBoundNanos)
                    .isLessThan(Long.MAX_VALUE);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = { 500_000_000L, 1_000_000_000L, 2_000_000_000L, 10_000_000_000L, Long.MAX_VALUE })
    void zeroJitterRateKeepsMaxConnectionAge(long maxConnectionAgeNanos) {
        assertThat(jitteredMaxConnectionAgeNanos(maxConnectionAgeNanos, 0.0)).isEqualTo(maxConnectionAgeNanos);
    }

    @Test
    void disabledMaxConnectionAgeStaysDisabled() {
        assertThat(jitteredMaxConnectionAgeNanos(0, 1.0)).isZero();
    }
}
