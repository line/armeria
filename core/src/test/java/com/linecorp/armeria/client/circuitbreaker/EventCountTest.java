/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class EventCountTest {

    @Test
    public void testCounts() {
        assertThat(new EventCount(0, 0).success()).isEqualTo(0L);
        assertThat(new EventCount(1, 0).success()).isEqualTo(1L);
        assertThat(new EventCount(1, 1).success()).isEqualTo(1L);
        assertThat(new EventCount(0, 0).failure()).isEqualTo(0L);
        assertThat(new EventCount(0, 1).failure()).isEqualTo(1L);
        assertThat(new EventCount(1, 1).failure()).isEqualTo(1L);
        assertThat(new EventCount(0, 0).total()).isEqualTo(0L);
        assertThat(new EventCount(1, 1).total()).isEqualTo(2L);
    }

    @Test
    public void testRates() {
        assertThatThrownBy(() -> new EventCount(0, 0).successRate()).isInstanceOf(ArithmeticException.class);

        assertThat(new EventCount(1, 0).successRate()).isEqualTo(1.0);
        assertThat(new EventCount(1, 1).successRate()).isEqualTo(0.5);

        assertThatThrownBy(() -> new EventCount(0, 0).failureRate()).isInstanceOf(ArithmeticException.class);

        assertThat(new EventCount(0, 1).failureRate()).isEqualTo(1.0);
        assertThat(new EventCount(1, 1).failureRate()).isEqualTo(0.5);
    }

    @Test
    public void testInvalidArguments() {
        assertThatThrownBy(() -> new EventCount(-1, 0)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> new EventCount(0, -1)).isInstanceOf(AssertionError.class);
    }

    @Test
    public void testEquals() {
        EventCount ec = new EventCount(1, 1);
        assertThat(ec).isEqualTo(ec);
        assertThat(new EventCount(0, 0)).isEqualTo(new EventCount(0, 0));
        assertThat(new EventCount(1, 0)).isNotEqualTo(new EventCount(0, 0));
        assertThat(new EventCount(1, 0)).isNotEqualTo(new Object());
    }
}
