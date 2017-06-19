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

import static com.linecorp.armeria.common.metric.MetricUnit.COUNT;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.Test;

public class DefaultHistogramTest {

    @Test
    public void constructorValidation() {
        final MetricKey k = new MetricKey("a");
        final MetricUnit u = COUNT;
        final String d = "";

        // Significant digit too small.
        assertThatThrownBy(() -> new DefaultHistogram(k, u, d, -1, Duration.ofMinutes(10), 5))
                .isInstanceOf(IllegalArgumentException.class);
        // Significant digit too large.
        assertThatThrownBy(() -> new DefaultHistogram(k, u, d, 6, Duration.ofMinutes(10), 5))
                .isInstanceOf(IllegalArgumentException.class);

        // Bucket duration < 1 sec.
        assertThatThrownBy(() -> new DefaultHistogram(k, u, d, 5, Duration.ofMinutes(1), 61))
                .isInstanceOf(IllegalArgumentException.class);

        // Invalid number of buckets.
        assertThatThrownBy(() -> new DefaultHistogram(k, u, d, 5, Duration.ofMinutes(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
