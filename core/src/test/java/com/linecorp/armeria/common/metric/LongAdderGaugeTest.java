/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.metric;

import static com.linecorp.armeria.common.metric.MetricUnit.BYTES_CUMULATIVE;
import static com.linecorp.armeria.common.metric.MetricUnit.COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class LongAdderGaugeTest {

    @Test
    public void basic() {
        final LongAdderGauge gauge = new LongAdderGauge(new MetricKey("foo"), BYTES_CUMULATIVE, "bar");

        // Initial value.
        assertThat(gauge.value()).isZero();

        // Increment by 1.
        gauge.inc();
        assertThat(gauge.value()).isOne();

        // Increment by 9.
        gauge.add(9);
        assertThat(gauge.value()).isEqualTo(10);

        // Basic getters
        assertThat(gauge.key()).isEqualTo(new MetricKey("foo"));
        assertThat(gauge.unit()).isEqualTo(BYTES_CUMULATIVE);
        assertThat(gauge.description()).isEqualTo("bar");
        assertThat(gauge.toString()).isEqualTo("foo=10(cumulative_bytes)");
    }

    @Test
    public void allowDecrement() {
        final LongAdderGauge gauge = new LongAdderGauge(new MetricKey("a"), COUNT, "", true);

        // Increment by a negative value.
        gauge.add(-1);
        assertThat(gauge.value()).isEqualTo(-1);

        // Decrement by a positive value.
        gauge.dec();
        assertThat(gauge.value()).isEqualTo(-2);

        // Overflow should not be treated specially.
        gauge.add(Long.MAX_VALUE);
        gauge.add(43);
        assertThat(gauge.value()).isEqualTo(-9223372036854775768L);
    }

    @Test
    public void disallowDecrement() {
        final LongAdderGauge gauge = new LongAdderGauge(new MetricKey("a"), COUNT, "");

        // Increment by a negative value.
        assertThatThrownBy(() -> gauge.add(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(gauge.value()).isZero();

        // Decrement by a positive value.
        assertThatThrownBy(gauge::dec).isInstanceOf(IllegalStateException.class);
        assertThat(gauge.value()).isZero();

        // Overflow should wrap around.
        gauge.add(Long.MAX_VALUE);
        gauge.add(43);
        assertThat(gauge.value()).isEqualTo(42);
    }
}
