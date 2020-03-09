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

package com.linecorp.armeria.server.throttling.tokenbucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.github.bucket4j.Bandwidth;

public class BandwidthLimitTest {

    @Test
    public void testConstructor() {
        final BandwidthLimit bl1 = BandwidthLimit.of(100L, 1000L, 50L, Duration.ofSeconds(60L));
        System.out.println(bl1);
        assertEquals(100L, bl1.limit());
        assertEquals(1000L, bl1.overdraftLimit());
        assertEquals(50L, bl1.initialSize());
        assertEquals(Duration.ofSeconds(60L), bl1.period());
        assertEquals("100;window=60;burst=1000", bl1.toSpecString());
        final Bandwidth b1 = bl1.bandwidth();
        System.out.println(b1);
        assertEquals(1000L, b1.getCapacity());
        assertEquals(100L, b1.getRefillTokens());
        assertEquals(50L, b1.getInitialTokens());
        assertEquals(Duration.ofSeconds(60L).toNanos(), b1.getRefillPeriodNanos());

        final BandwidthLimit bl2 = BandwidthLimit.of(100L, 1000L, Duration.ofSeconds(60L));
        System.out.println(bl2);
        assertEquals(100L, bl2.limit());
        assertEquals(1000L, bl2.overdraftLimit());
        assertEquals(0L, bl2.initialSize());
        assertEquals(Duration.ofSeconds(60L), bl2.period());
        assertEquals("100;window=60;burst=1000", bl2.toSpecString());
        final Bandwidth b2 = bl2.bandwidth();
        System.out.println(b2);
        assertEquals(1000L, b2.getCapacity());
        assertEquals(100L, b2.getRefillTokens());
        assertEquals(100L, b2.getInitialTokens());
        assertEquals(Duration.ofSeconds(60L).toNanos(), b2.getRefillPeriodNanos());

        final BandwidthLimit bl3 = BandwidthLimit.of(100L, Duration.ofSeconds(60L));
        System.out.println(bl3);
        assertEquals(100L, bl3.limit());
        assertEquals(0L, bl3.overdraftLimit());
        assertEquals(0L, bl3.initialSize());
        assertEquals(Duration.ofSeconds(60L), bl3.period());
        assertEquals("100;window=60", bl3.toSpecString());
        final Bandwidth b3 = bl3.bandwidth();
        System.out.println(b3);
        assertEquals(100L, b3.getCapacity());
        assertEquals(100L, b3.getRefillTokens());
        assertEquals(100L, b3.getInitialTokens());
        assertEquals(Duration.ofSeconds(60L).toNanos(), b3.getRefillPeriodNanos());

        final BandwidthLimit bl4 = BandwidthLimit.of(100L, 0L, Duration.ofSeconds(60L));
        System.out.println(bl4);
        assertEquals(100L, bl4.limit());
        assertEquals(0L, bl4.overdraftLimit());
        assertEquals(0L, bl4.initialSize());
        assertEquals(Duration.ofSeconds(60L), bl4.period());
        assertEquals("100;window=60", bl4.toSpecString());
        final Bandwidth b4 = bl4.bandwidth();
        System.out.println(b4);
        assertEquals(100L, b4.getCapacity());
        assertEquals(100L, b4.getRefillTokens());
        assertEquals(100L, b4.getInitialTokens());
        assertEquals(Duration.ofSeconds(60L).toNanos(), b4.getRefillPeriodNanos());
    }

    @Test
    public void testInvalidConstructor() {
        try {
            BandwidthLimit.of(0L, 1000L, 50L, Duration.ofSeconds(60L));
        } catch (IllegalArgumentException e) {
            assertEquals(
                    "Bandwidth limit must be positive. Found: 0", e.getMessage());
        }

        try {
            BandwidthLimit.of(100L, 99L, 50L, Duration.ofSeconds(60L));
        } catch (IllegalArgumentException e) {
            assertEquals(
                    "Overdraft limit has to exceed bandwidth limit 100. Found: 99", e.getMessage());
        }

        try {
            BandwidthLimit.of(100L, 1000L, 50L, Duration.ofSeconds(0L));
        } catch (IllegalArgumentException e) {
            assertEquals(
                    "Bandwidth period must be positive. Found: PT0S", e.getMessage());
        }
    }

    @Test
    public void testSpecification() {
        final BandwidthLimit bl1 = BandwidthLimit.of("100;window=60;burst=1000;initial=50");
        System.out.println(bl1);
        assertEquals(100L, bl1.limit());
        assertEquals(1000L, bl1.overdraftLimit());
        assertEquals(50L, bl1.initialSize());
        assertEquals(Duration.ofSeconds(60L), bl1.period());
        assertNotNull(bl1.bandwidth());

        final BandwidthLimit bl2 = BandwidthLimit.of("100;window=60;burst=1000");
        System.out.println(bl2);
        assertEquals(100L, bl2.limit());
        assertEquals(1000L, bl2.overdraftLimit());
        assertEquals(0L, bl2.initialSize());
        assertEquals(Duration.ofSeconds(60L), bl2.period());
        assertNotNull(bl2.bandwidth());

        final BandwidthLimit bl3 = BandwidthLimit.of("100;window=60");
        System.out.println(bl3);
        assertEquals(100L, bl3.limit());
        assertEquals(0L, bl3.overdraftLimit());
        assertEquals(0L, bl3.initialSize());
        assertEquals(Duration.ofSeconds(60L), bl3.period());
        assertNotNull(bl3.bandwidth());
    }

    @Test
    public void testInvalidSpecification() {
        try {
            BandwidthLimit.of("1000000000000000000000000000000000000000000000000000000000000000000;window=60");
        } catch (NumberFormatException e) {
            assertEquals(
                    "For input string: \"1000000000000000000000000000000000000000000000000000000000000000000\"",
                         e.getMessage());
        }

        try {
            BandwidthLimit.of("abcd;window=60");
        } catch (NumberFormatException e) {
            assertEquals(
                    "For input string: \"abcd\"",
                         e.getMessage());
        }

        try {
            BandwidthLimit.of("100;window=defg");
        } catch (NumberFormatException e) {
            assertEquals(
                    "For input string: \"defg\"",
                         e.getMessage());
        }

        try {
            BandwidthLimit.of("100;");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid format of \"100;\" - period not found", e.getMessage());
        }

        try {
            BandwidthLimit.of(";window=60;burst=1000;initial=50");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid format of \";window=60;burst=1000;initial=50\" - limit not found",
                         e.getMessage());
        }

        try {
            BandwidthLimit.of("100");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid format of \"100\" - period not found", e.getMessage());
        }

        try {
            BandwidthLimit.of("");
        } catch (IllegalArgumentException e) {
            assertEquals("Empty bandwidth limit specification", e.getMessage());
        }

        try {
            BandwidthLimit.of(null);
        } catch (NullPointerException e) {
            assertEquals("specification", e.getMessage());
        }
    }
}
