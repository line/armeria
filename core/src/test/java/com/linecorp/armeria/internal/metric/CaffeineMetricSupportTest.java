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

package com.linecorp.armeria.internal.metric;

import static com.linecorp.armeria.common.metric.MeterRegistryUtil.measure;
import static com.linecorp.armeria.internal.metric.CaffeineMetricSupport.UPDATE_INTERVAL_NANOS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.collect.ImmutableList;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.prometheus.PrometheusMeterRegistry;
import io.micrometer.core.instrument.util.MeterId;

public class CaffeineMetricSupportTest {

    @Test
    public void test() {
        final LoadingCache<?, ?> cache = mock(LoadingCache.class);
        when(cache.stats()).thenReturn(new CacheStats(1, 2, 3, 4, 5, 6, 7));
        when(cache.estimatedSize()).thenReturn(8L);

        final AtomicLong ticker = new AtomicLong();
        final MeterRegistry registry = new PrometheusMeterRegistry();
        CaffeineMetricSupport.setup(registry, new MeterId("foo", ImmutableList.of()), cache, ticker::get);

        verify(cache, times(1)).stats();
        verify(cache, times(1)).estimatedSize();

        assertThat(measure(registry, "foo_requests_total", "result", "hit")).isEqualTo(1);
        assertThat(measure(registry, "foo_requests_total", "result", "miss")).isEqualTo(2);
        assertThat(measure(registry, "foo_loads_total", "result", "success")).isEqualTo(3);
        assertThat(measure(registry, "foo_loads_total", "result", "failure")).isEqualTo(4);
        assertThat(measure(registry, "foo_load_duration_seconds_total")).isEqualTo(5e-9);
        assertThat(measure(registry, "foo_eviction_total")).isEqualTo(6);
        assertThat(measure(registry, "foo_eviction_weight_total")).isEqualTo(7);
        assertThat(measure(registry, "foo_estimated_size")).isEqualTo(8);

        // Make sure Cache.stats() and estimatedSize() are not called since the initial update.
        verify(cache, times(1)).stats();
        verify(cache, times(1)).estimatedSize();

        // Advance the ticker so that the next collection triggers stats() and estimatedSize().
        ticker.addAndGet(UPDATE_INTERVAL_NANOS);
        when(cache.stats()).thenReturn(new CacheStats(9, 10, 11, 12, 13, 14, 15));
        when(cache.estimatedSize()).thenReturn(16L);

        assertThat(measure(registry, "foo_requests_total", "result", "hit")).isEqualTo(9);
        assertThat(measure(registry, "foo_requests_total", "result", "miss")).isEqualTo(10);
        assertThat(measure(registry, "foo_loads_total", "result", "success")).isEqualTo(11);
        assertThat(measure(registry, "foo_loads_total", "result", "failure")).isEqualTo(12);
        assertThat(measure(registry, "foo_load_duration_seconds_total")).isEqualTo(13e-9);
        assertThat(measure(registry, "foo_eviction_total")).isEqualTo(14);
        assertThat(measure(registry, "foo_eviction_weight_total")).isEqualTo(15);
        assertThat(measure(registry, "foo_estimated_size")).isEqualTo(16);

        // Make sure Cache.stats() and estimatedSize() were called once more since the initial update.
        verify(cache, times(2)).stats();
        verify(cache, times(2)).estimatedSize();
    }

    @Test
    public void testNonLoadingCache() {
        final Cache<?, ?> cache = mock(Cache.class);
        when(cache.stats()).thenReturn(new CacheStats(1, 2, 0, 0, 0, 3, 4));
        when(cache.estimatedSize()).thenReturn(5L);

        final AtomicLong ticker = new AtomicLong();
        final MeterRegistry registry = new PrometheusMeterRegistry();
        CaffeineMetricSupport.setup(registry, new MeterId("bar", ImmutableList.of()), cache, ticker::get);

        verify(cache, times(1)).stats();
        verify(cache, times(1)).estimatedSize();

        assertThat(measure(registry, "bar_requests_total", "result", "hit")).isEqualTo(1);
        assertThat(measure(registry, "bar_requests_total", "result", "miss")).isEqualTo(2);
        assertThat(measure(registry, "bar_eviction_total")).isEqualTo(3);
        assertThat(measure(registry, "bar_eviction_weight_total")).isEqualTo(4);
        assertThat(measure(registry, "bar_estimated_size")).isEqualTo(5);

        // Make sure the meters related with loading are not registered.
        assertThatThrownBy(() -> measure(registry, "bar_loads_total", "result", "success"))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> measure(registry, "bar_loads_total", "result", "failure"))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> measure(registry, "bar_load_duration_seconds_total"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
