/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.throttling.bucket4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.github.bucket4j.Bandwidth;

class BandwidthLimitTest {

    @Test
    void testConstructor() {
        final BandwidthLimit bl1 = BandwidthLimit.of(100L, 1000L, 50L, Duration.ofSeconds(60L));
        assertThat(bl1.limit()).isEqualTo(100L);
        assertThat(bl1.overdraftLimit()).isEqualTo(1000L);
        assertThat(bl1.initialSize()).isEqualTo(50L);
        assertThat(bl1.period()).isEqualTo(Duration.ofSeconds(60L));
        assertThat(bl1.toSpecString()).isEqualTo("100;window=60;burst=1000");
        final Bandwidth b1 = bl1.bandwidth();
        assertThat(b1.getCapacity()).isEqualTo(1000L);
        assertThat(b1.getRefillTokens()).isEqualTo(100L);
        assertThat(b1.getInitialTokens()).isEqualTo(50L);
        assertThat(b1.getRefillPeriodNanos()).isEqualTo(Duration.ofSeconds(60L).toNanos());

        final BandwidthLimit bl2 = BandwidthLimit.of(100L, 1000L, Duration.ofSeconds(60L));
        assertThat(bl2.limit()).isEqualTo(100L);
        assertThat(bl2.overdraftLimit()).isEqualTo(1000L);
        assertThat(bl2.initialSize()).isEqualTo(0L);
        assertThat(bl2.period()).isEqualTo(Duration.ofSeconds(60L));
        assertThat(bl2.toSpecString()).isEqualTo("100;window=60;burst=1000");
        final Bandwidth b2 = bl2.bandwidth();
        assertThat(b2.getCapacity()).isEqualTo(1000L);
        assertThat(b2.getRefillTokens()).isEqualTo(100L);
        assertThat(b2.getInitialTokens()).isEqualTo(100L);
        assertThat(b2.getRefillPeriodNanos()).isEqualTo(Duration.ofSeconds(60L).toNanos());

        final BandwidthLimit bl3 = BandwidthLimit.of(100L, Duration.ofSeconds(60L));
        assertThat(bl3.limit()).isEqualTo(100L);
        assertThat(bl3.overdraftLimit()).isEqualTo(0L);
        assertThat(bl3.initialSize()).isEqualTo(0L);
        assertThat(bl3.period()).isEqualTo(Duration.ofSeconds(60L));
        assertThat(bl3.toSpecString()).isEqualTo("100;window=60");
        final Bandwidth b3 = bl3.bandwidth();
        assertThat(b3.getCapacity()).isEqualTo(100L);
        assertThat(b3.getRefillTokens()).isEqualTo(100L);
        assertThat(b3.getInitialTokens()).isEqualTo(100L);
        assertThat(b3.getRefillPeriodNanos()).isEqualTo(Duration.ofSeconds(60L).toNanos());

        final BandwidthLimit bl4 = BandwidthLimit.of(100L, 0L, Duration.ofSeconds(60L));
        assertThat(bl4.limit()).isEqualTo(100L);
        assertThat(bl4.overdraftLimit()).isEqualTo(0L);
        assertThat(bl4.initialSize()).isEqualTo(0L);
        assertThat(bl4.period()).isEqualTo(Duration.ofSeconds(60L));
        assertThat(bl4.toSpecString()).isEqualTo("100;window=60");
        final Bandwidth b4 = bl4.bandwidth();
        assertThat(b4.getCapacity()).isEqualTo(100L);
        assertThat(b4.getRefillTokens()).isEqualTo(100L);
        assertThat(b4.getInitialTokens()).isEqualTo(100L);
        assertThat(b4.getRefillPeriodNanos()).isEqualTo(Duration.ofSeconds(60L).toNanos());
    }

    @Test
    void testInvalidConstructor() {
        assertThatThrownBy(
                () -> BandwidthLimit.of(0L, 1000L, 50L, Duration.ofSeconds(60L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit: 0 (expected: > 0)");

        assertThatThrownBy(
                () -> BandwidthLimit.of(100L, 99L, 50L, Duration.ofSeconds(60L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overdraftLimit: 99 (expected: > 100)");

        assertThatThrownBy(
                () -> BandwidthLimit.of(100L, 1000L, 50L, Duration.ofSeconds(0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period: PT0S (expected: > PT0S)");
    }

    @Test
    void testSpecification() {
        final BandwidthLimit bl1 = BandwidthLimit.of("100;window=60;burst=1000;initial=50");
        assertThat(bl1.limit()).isEqualTo(100L);
        assertThat(bl1.overdraftLimit()).isEqualTo(1000L);
        assertThat(bl1.initialSize()).isEqualTo(50L);
        assertThat(bl1.period()).isEqualTo(Duration.ofSeconds(60L));
        assertThat(bl1.bandwidth()).isNotNull();

        final BandwidthLimit bl2 = BandwidthLimit.of("100;window=60;burst=1000");
        assertThat(bl2.limit()).isEqualTo(100L);
        assertThat(bl2.overdraftLimit()).isEqualTo(1000L);
        assertThat(bl2.initialSize()).isEqualTo(0L);
        assertThat(bl2.period()).isEqualTo(Duration.ofSeconds(60L));
        assertThat(bl2.bandwidth()).isNotNull();

        final BandwidthLimit bl3 = BandwidthLimit.of("100;window=60");
        assertThat(bl3.limit()).isEqualTo(100L);
        assertThat(bl3.overdraftLimit()).isEqualTo(0L);
        assertThat(bl3.initialSize()).isEqualTo(0L);
        assertThat(bl3.period()).isEqualTo(Duration.ofSeconds(60L));
        assertThat(bl3.bandwidth()).isNotNull();
    }

    @Test
    void testInvalidSpecification() {
        assertThatThrownBy(
                () -> BandwidthLimit.of(
                  "1000000000000000000000000000000000000000000000000000000000000000000;window=60"))
                .isInstanceOf(NumberFormatException.class)
                .hasMessageContaining(
                  "For input string: \"1000000000000000000000000000000000000000000000000000000000000000000\"");

        assertThatThrownBy(
                () -> BandwidthLimit.of("abcd;window=60"))
                .isInstanceOf(NumberFormatException.class)
                .hasMessageContaining("For input string: \"abcd\"");

        assertThatThrownBy(
                () -> BandwidthLimit.of("100;window=defg"))
                .isInstanceOf(NumberFormatException.class)
                .hasMessageContaining("For input string: \"defg\"");

        assertThatThrownBy(
                () -> BandwidthLimit.of("100;"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid format of \"100;\" - period not found");

        assertThatThrownBy(
                () -> BandwidthLimit.of(";window=60;burst=1000;initial=50"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Invalid format of \";window=60;burst=1000;initial=50\" - limit not found");

        assertThatThrownBy(
                () -> BandwidthLimit.of("100"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid format of \"100\" - period not found");

        assertThatThrownBy(
                () -> BandwidthLimit.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Empty bandwidth limit specification");

        //noinspection ConstantConditions
        assertThatThrownBy(
                () -> BandwidthLimit.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("specification");
    }
}
