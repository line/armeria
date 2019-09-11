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

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CountingSamplerTest {
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testSamplingRateMinimumLimit() throws Exception {
        thrown.expect(IllegalArgumentException.class);
        CountingSampler.create(0.0001f);
    }

    @Test
    public void testSamplingRateMaximumLimit() throws Exception {
        thrown.expect(IllegalArgumentException.class);
        CountingSampler.create(1.0001f);
    }

    @Test
    public void testNeverSampledSampler() throws Exception {
        assertThat(CountingSampler.create(0)).isSameAs(Sampler.never());
    }

    @Test
    public void testAlwaysSampledSampler() throws Exception {
        assertThat(CountingSampler.create(1.0)).isSameAs(Sampler.always());
    }
}
