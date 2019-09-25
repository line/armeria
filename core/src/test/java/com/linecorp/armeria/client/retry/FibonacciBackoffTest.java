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
package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FibonacciBackoffTest {

    @Test
    void testNextDelay() {
        final Backoff backoff = new FibonacciBackoff(10, 120);
        assertThat(backoff.nextDelayMillis(1)).isEqualTo(10);
        assertThat(backoff.nextDelayMillis(2)).isEqualTo(10);
        assertThat(backoff.nextDelayMillis(3)).isEqualTo(20);
        assertThat(backoff.nextDelayMillis(4)).isEqualTo(30);
        assertThat(backoff.nextDelayMillis(7)).isEqualTo(120);
    }

    @Test
    void testLargeNumberOfRetries() {
        final Backoff backoff = new FibonacciBackoff(1, Long.MAX_VALUE);
        assertThat(backoff.nextDelayMillis(30)).isEqualTo(832040);
        assertThat(backoff.nextDelayMillis(31)).isEqualTo(1346269);
        assertThat(backoff.nextDelayMillis(32)).isEqualTo(2178309);
    }

    @Test
    void testOverflow() {
        final Backoff backoff = new FibonacciBackoff(Long.MAX_VALUE / 3, Long.MAX_VALUE);
        assertThat(backoff.nextDelayMillis(1)).isEqualTo(Long.MAX_VALUE / 3);
        assertThat(backoff.nextDelayMillis(2)).isEqualTo(Long.MAX_VALUE / 3);
        assertThat(backoff.nextDelayMillis(3)).isEqualTo(Long.MAX_VALUE / 3 * 2);
        assertThat(backoff.nextDelayMillis(4)).isEqualTo(Long.MAX_VALUE / 3 * 3);
        assertThat(backoff.nextDelayMillis(5)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void testConstraintInitialDelay() {
        assertThatThrownBy(() -> new FibonacciBackoff(-5, 120))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testConstraintMaxDelay() {
        assertThatThrownBy(() -> new FibonacciBackoff(10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
