/*
 * Copyright 2023 LINE Corporation
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

import org.junit.jupiter.api.Test;

public class TimeWindowPercentileSamplerTest {
    @Test
    public void testSamplingMinimumPercentile() {
        TimeWindowPercentileSampler.SNAPSHOT_UPDATE_MILLIS = 0;
        final Sampler<Long> sampler = TimeWindowPercentileSampler.create(0.0f, 10000000L);

        // Should sample everything
        assertThat(sampler.isSampled(10L)).isTrue();
        assertThat(sampler.isSampled(20L)).isTrue();
        assertThat(sampler.isSampled(0L)).isTrue();
    }

    @Test
    public void testSamplingMaximumPercentile() {
        TimeWindowPercentileSampler.SNAPSHOT_UPDATE_MILLIS = 0;
        final Sampler<Long> sampler = TimeWindowPercentileSampler.create(1.0f, 10000000L);

        // Should only sample the maximum value
        assertThat(sampler.isSampled(10L)).isTrue();
        assertThat(sampler.isSampled(20L)).isTrue();
        assertThat(sampler.isSampled(0L)).isFalse();
        assertThat(sampler.isSampled(19L)).isFalse();
        assertThat(sampler.isSampled(20L)).isTrue();
        assertThat(sampler.isSampled(21L)).isTrue();
        assertThat(sampler.isSampled(20L)).isFalse();
    }

    @Test
    public void testSamplingWindowExpires() throws InterruptedException {
        final long windowLength = 1000L;
        TimeWindowPercentileSampler.SNAPSHOT_UPDATE_MILLIS = 0;
        final Sampler<Long> sampler = TimeWindowPercentileSampler.create(1.0f, windowLength);

        // Should only sample the maximum value
        assertThat(sampler.isSampled(20L)).isTrue();
        assertThat(sampler.isSampled(19L)).isFalse();

        // Sliding window expires, new maximum should be 19
        Thread.sleep(windowLength + 1L);
        assertThat(sampler.isSampled(19L)).isTrue();
    }

    @Test
    public void testSampling0_5Percentile() {
        TimeWindowPercentileSampler.SNAPSHOT_UPDATE_MILLIS = 0;
        final Sampler<Long> sampler = TimeWindowPercentileSampler.create(.5f, 10000L);

        // Create a uniform distribution of 1000 values from 1 to 1000
        for (long i = 1; i <= 1000; i++) {
            sampler.isSampled(i);
        }

        // 0.5 percentile should be approximately 500
        assertThat(sampler.isSampled(501L)).isTrue();
        assertThat(sampler.isSampled(499L)).isFalse();
    }

    @Test
    public void testSampling0_95Percentile() {
        TimeWindowPercentileSampler.SNAPSHOT_UPDATE_MILLIS = 0;
        final Sampler<Long> sampler = TimeWindowPercentileSampler.create(.95f, 10000L);

        // Create a uniform distribution of 1000 values from 1 to 1000
        for (long i = 1; i <= 1000; i++) {
            sampler.isSampled(i);
        }

        // 0.95 percentile is roughly 950, but not exactly
        assertThat(sampler.isSampled(951L)).isTrue();
        assertThat(sampler.isSampled(952L)).isTrue();
        assertThat(sampler.isSampled(0L)).isFalse();
    }
}
