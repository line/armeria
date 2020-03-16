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
/*
 * Copyright 2013-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class CountingSamplerTest {
    @Test
    public void testSamplingRateMinimumLimit() {
        assertThatThrownBy(() -> CountingSampler.create(-1.99f)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testSamplingRateMaximumLimit() {
        assertThatThrownBy(() -> CountingSampler.create(1.01f)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testSamplingRatePercentageRounding() {
        assertThat(CountingSampler.create(0.01f)).isInstanceOfSatisfying(CountingSampler.class, sampler -> {
            assertThat(sampler.sampleDecisions.cardinality()).isOne();
        });
    }

    @Test
    public void testNeverSampledSampler() {
        assertThat(CountingSampler.create(0.0f)).isSameAs(Sampler.never());
    }

    @Test
    public void testAlwaysSampledSampler() {
        assertThat(CountingSampler.create(1.0f)).isSameAs(Sampler.always());
    }
}
