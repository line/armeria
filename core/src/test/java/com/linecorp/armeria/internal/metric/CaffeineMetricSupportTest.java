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

import static com.linecorp.armeria.internal.metric.CaffeineMetricSupport.UPDATE_INTERVAL_NANOS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.metric.MeterId;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistry;

import io.micrometer.core.instrument.MeterRegistry;

public class CaffeineMetricSupportTest {

    @Test
    public void test() {
        final LoadingCache<?, ?> cache = mock(LoadingCache.class);
        when(cache.stats()).thenReturn(new CacheStats(1, 2, 3, 4, 5, 6, 7));
        when(cache.estimatedSize()).thenReturn(8L);

        final AtomicLong ticker = new AtomicLong();
        final MeterRegistry registry = new PrometheusMeterRegistry();
        CaffeineMetricSupport.setup(registry, new MeterId("foo"), cache, ticker::get);

        verify(cache, times(1)).stats();
        verify(cache, times(1)).estimatedSize();

        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("foo.requests#count{result=hit}", 1.0)
                .containsEntry("foo.requests#count{result=miss}", 2.0)
                .containsEntry("foo.loads#count{result=success}", 3.0)
                .containsEntry("foo.loads#count{result=failure}", 4.0)
                .containsEntry("foo.loadDuration#count", 5.0)
                .containsEntry("foo.evictions#count", 6.0)
                .containsEntry("foo.evictionWeight#count", 7.0)
                .containsEntry("foo.estimatedSize#value", 8.0);

        // Make sure Cache.stats() and estimatedSize() are not called since the initial update.
        verify(cache, times(1)).stats();
        verify(cache, times(1)).estimatedSize();

        // Advance the ticker so that the next collection triggers stats() and estimatedSize().
        ticker.addAndGet(UPDATE_INTERVAL_NANOS);
        when(cache.stats()).thenReturn(new CacheStats(9, 10, 11, 12, 13, 14, 15));
        when(cache.estimatedSize()).thenReturn(16L);

        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("foo.requests#count{result=hit}", 9.0)
                .containsEntry("foo.requests#count{result=miss}", 10.0)
                .containsEntry("foo.loads#count{result=success}", 11.0)
                .containsEntry("foo.loads#count{result=failure}", 12.0)
                .containsEntry("foo.loadDuration#count", 13.0)
                .containsEntry("foo.evictions#count", 14.0)
                .containsEntry("foo.evictionWeight#count", 15.0)
                .containsEntry("foo.estimatedSize#value", 16.0);

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

        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("bar.requests#count{result=hit}", 1.0)
                .containsEntry("bar.requests#count{result=miss}", 2.0)
                .containsEntry("bar.evictions#count", 3.0)
                .containsEntry("bar.evictionWeight#count", 4.0)
                .containsEntry("bar.estimatedSize#value", 5.0);

        // Make sure the meters related with loading are not registered.
        assertThat(MoreMeters.measureAll(registry)).doesNotContainKeys(
                "bar.loads#count{result=success}",
                "bar.loads#count{result=failure}",
                "bar.loadDuration#count");
    }
}
