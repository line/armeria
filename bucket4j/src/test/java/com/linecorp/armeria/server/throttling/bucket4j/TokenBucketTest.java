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

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    @Test
    void testBuilder1() {
        final TokenBucket tb1 =
                TokenBucket.builder()
                           .limit(100L, 1000L, 50L, Duration.ofSeconds(60L))
                           .limit(50000, Duration.ofHours(1L))
                           .build();

        final BandwidthLimit[] limits1 = tb1.limits();
        assertThat(limits1.length).isEqualTo(2);

        assertThat(limits1[0].limit()).isEqualTo(100L);
        assertThat(limits1[0].overdraftLimit()).isEqualTo(1000L);
        assertThat(limits1[0].initialSize()).isEqualTo(50L);
        assertThat(limits1[0].period()).isEqualTo(Duration.ofSeconds(60L));

        assertThat(limits1[1].limit()).isEqualTo(50000L);
        assertThat(limits1[1].overdraftLimit()).isEqualTo(0L);
        assertThat(limits1[1].initialSize()).isEqualTo(0L);
        assertThat(limits1[1].period()).isEqualTo(Duration.ofSeconds(3600L));

        assertThat(tb1.lowestLimit()).isSameAs(limits1[0]);
        assertThat(tb1.toSpecString()).isEqualTo("100, 100;window=60;burst=1000, 50000;window=3600");
    }

    @Test
    void testBuilder2() {
        final TokenBucket tb1 =
                TokenBucket.builder()
                           .limit(100L, 1000L, Duration.ofSeconds(60L))
                           .build();

        final BandwidthLimit[] limits1 = tb1.limits();
        assertThat(limits1.length).isEqualTo(1);

        assertThat(limits1[0].limit()).isEqualTo(100L);
        assertThat(limits1[0].overdraftLimit()).isEqualTo(1000L);
        assertThat(limits1[0].initialSize()).isEqualTo(0L);
        assertThat(limits1[0].period()).isEqualTo(Duration.ofSeconds(60L));

        assertThat(tb1.lowestLimit()).isSameAs(limits1[0]);
        assertThat(tb1.toSpecString()).isEqualTo("100, 100;window=60;burst=1000");
    }

    @Test
    void testBuilder3() {
        final TokenBucket tb1 =
                TokenBucket.builder()
                           .limits()
                           .build();

        final BandwidthLimit[] limits1 = tb1.limits();
        assertThat(limits1.length).isEqualTo(0);
        assertThat(tb1.lowestLimit()).isNull();
    }

    @Test
    void testSpecification1() {
        final TokenBucket tb1 = TokenBucket.of("100;window=60;burst=1000, 50000;window=3600");

        final BandwidthLimit[] limits1 = tb1.limits();
        assertThat(limits1.length).isEqualTo(2);

        assertThat(limits1[0].limit()).isEqualTo(100L);
        assertThat(limits1[0].overdraftLimit()).isEqualTo(1000L);
        assertThat(limits1[0].initialSize()).isEqualTo(0L);
        assertThat(limits1[0].period()).isEqualTo(Duration.ofSeconds(60L));

        assertThat(limits1[1].limit()).isEqualTo(50000L);
        assertThat(limits1[1].overdraftLimit()).isEqualTo(0L);
        assertThat(limits1[1].initialSize()).isEqualTo(0L);
        assertThat(limits1[1].period()).isEqualTo(Duration.ofSeconds(3600L));

        assertThat(tb1.lowestLimit()).isSameAs(limits1[0]);
        assertThat(tb1.toSpecString()).isEqualTo("100, 100;window=60;burst=1000, 50000;window=3600");
    }

    @Test
    void testSpecification2() {
        final TokenBucket tb1 = TokenBucket.of("100;window=60;burst=1000");

        final BandwidthLimit[] limits1 = tb1.limits();
        assertThat(limits1.length).isEqualTo(1);

        assertThat(limits1[0].limit()).isEqualTo(100L);
        assertThat(limits1[0].overdraftLimit()).isEqualTo(1000L);
        assertThat(limits1[0].initialSize()).isEqualTo(0L);
        assertThat(limits1[0].period()).isEqualTo(Duration.ofSeconds(60L));
        assertThat(tb1.lowestLimit()).isSameAs(limits1[0]);

        assertThat(tb1.toSpecString()).isEqualTo("100, 100;window=60;burst=1000");
    }

    @Test
    void testSpecification3() {
        final TokenBucket tb1 = TokenBucket.of("");

        final BandwidthLimit[] limits1 = tb1.limits();
        assertThat(limits1.length).isEqualTo(0);
        assertThat(tb1.lowestLimit()).isNull();
    }
}
