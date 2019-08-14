/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExponentialBackoffTest {

    @Test
    void normal() {
        final Backoff backoff = new ExponentialBackoff(10, 120, 3.0);
        assertThat(backoff.nextDelayMillis(1)).isEqualTo(10);
        assertThat(backoff.nextDelayMillis(2)).isEqualTo(30);
        assertThat(backoff.nextDelayMillis(3)).isEqualTo(90);
        assertThat(backoff.nextDelayMillis(4)).isEqualTo(120);
        assertThat(backoff.nextDelayMillis(5)).isEqualTo(120);
        assertThat(backoff.nextDelayMillis(6)).isEqualTo(120);
        assertThat(backoff.nextDelayMillis(7)).isEqualTo(120);
    }

    @Test
    void nonPrecomputed() {
        final Backoff backoff = new ExponentialBackoff(10, Long.MAX_VALUE, 2.0);
        assertThat(backoff.nextDelayMillis(1)).isEqualTo(10);
        assertThat(backoff.nextDelayMillis(2)).isEqualTo(20);
        assertThat(backoff.nextDelayMillis(3)).isEqualTo(40);
        assertThat(backoff.nextDelayMillis(30)).isEqualTo(5368709120L);
        // Not precomputed, should fallback to computation and return a correct value.
        assertThat(backoff.nextDelayMillis(31)).isEqualTo(10737418240L);
    }

    @Test
    void overflow() {
        final Backoff backoff = new ExponentialBackoff(Long.MAX_VALUE / 3, Long.MAX_VALUE, 2.0);
        assertThat(backoff.nextDelayMillis(1)).isEqualTo(Long.MAX_VALUE / 3);
        assertThat(backoff.nextDelayMillis(2)).isEqualTo((long) (Long.MAX_VALUE / 3 * 2.0));
        assertThat(backoff.nextDelayMillis(3)).isEqualTo(Long.MAX_VALUE);
        assertThat(backoff.nextDelayMillis(4)).isEqualTo(Long.MAX_VALUE);
    }
}
