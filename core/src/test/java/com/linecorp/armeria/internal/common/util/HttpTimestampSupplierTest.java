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

package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HttpTimestampSupplierTest {

    private static final Instant TIME0 = Instant.parse("2019-10-18T10:15:30.05Z");
    private static final Instant TIME1 = Instant.parse("2019-10-18T10:15:31.25Z");

    @Mock private Clock clock;

    private HttpTimestampSupplier supplier;

    @BeforeEach
    void setUp() {
        supplier = new HttpTimestampSupplier(clock);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @Test
    void normal() {
        when(clock.instant()).thenReturn(TIME0);
        final String timestamp1 = supplier.currentTimestamp();
        final String timestamp2 = supplier.currentTimestamp();
        assertThat(timestamp1).isEqualTo("Fri, 18 Oct 2019 10:15:30 GMT");
        assertThat(timestamp1).isSameAs(timestamp2);
        when(clock.instant()).thenReturn(TIME1);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(
                () -> assertThat(supplier.currentTimestamp())
                        .isEqualTo("Fri, 18 Oct 2019 10:15:31 GMT"));
    }
}
