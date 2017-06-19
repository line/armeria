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

import static com.linecorp.armeria.internal.metric.CaffeineMetrics.UPDATE_INTERVAL_NANOS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import com.linecorp.armeria.common.metric.Gauge;
import com.linecorp.armeria.common.metric.MetricKey;
import com.linecorp.armeria.common.metric.Metrics;

public class CaffeineMetricsTest {

    @Test
    public void test() {
        final Cache<?, ?> cache = mock(Cache.class);
        when(cache.stats()).thenReturn(new CacheStats(1, 2, 3, 4, 5, 6, 7));
        when(cache.estimatedSize()).thenReturn(8L);

        final AtomicLong ticker = new AtomicLong();
        final Metrics metrics = new Metrics();
        final CaffeineMetrics m = new CaffeineMetrics(metrics, new MetricKey("foo"), cache, ticker::get);

        verify(cache, times(1)).stats();
        verify(cache, times(1)).estimatedSize();

        assertThat(metrics.metric(new MetricKey("foo", "hit"), Gauge.class))
                .isSameAs(m.hit()).satisfies(new GaugeValueRequirement(1));
        assertThat(metrics.metric(new MetricKey("foo", "miss"), Gauge.class))
                .isSameAs(m.miss()).satisfies(new GaugeValueRequirement(2));
        assertThat(metrics.metric(new MetricKey("foo", "total"), Gauge.class))
                .isSameAs(m.total()).satisfies(new GaugeValueRequirement(3));
        assertThat(metrics.metric(new MetricKey("foo", "loadSuccess"), Gauge.class))
                .isSameAs(m.loadSuccess()).satisfies(new GaugeValueRequirement(3));
        assertThat(metrics.metric(new MetricKey("foo", "loadFailure"), Gauge.class))
                .isSameAs(m.loadFailure()).satisfies(new GaugeValueRequirement(4));
        assertThat(metrics.metric(new MetricKey("foo", "loadTotal"), Gauge.class))
                .isSameAs(m.loadTotal()).satisfies(new GaugeValueRequirement(7));
        assertThat(metrics.metric(new MetricKey("foo", "loadDuration"), Gauge.class))
                .isSameAs(m.loadDuration()).satisfies(new GaugeValueRequirement(5));
        assertThat(metrics.metric(new MetricKey("foo", "eviction"), Gauge.class))
                .isSameAs(m.eviction()).satisfies(new GaugeValueRequirement(6));
        assertThat(metrics.metric(new MetricKey("foo", "evictionWeight"), Gauge.class))
                .isSameAs(m.evictionWeight()).satisfies(new GaugeValueRequirement(7));
        assertThat(metrics.metric(new MetricKey("foo", "estimatedSize"), Gauge.class))
                .isSameAs(m.estimatedSize()).satisfies(new GaugeValueRequirement(8));

        // Make sure Cache.stats() and estimatedSize() are not called since the initial update.
        verify(cache, times(1)).stats();
        verify(cache, times(1)).estimatedSize();

        // Advance the ticker so that the next collection triggers stats() and estimatedSize().
        ticker.addAndGet(UPDATE_INTERVAL_NANOS);
        when(cache.stats()).thenReturn(new CacheStats(9, 10, 11, 12, 13, 14, 15));
        when(cache.estimatedSize()).thenReturn(16L);

        assertThat(metrics.metric(new MetricKey("foo", "hit"), Gauge.class).value()).isEqualTo(9);
        assertThat(metrics.metric(new MetricKey("foo", "miss"), Gauge.class).value()).isEqualTo(10);
        assertThat(metrics.metric(new MetricKey("foo", "total"), Gauge.class).value()).isEqualTo(19);
        assertThat(metrics.metric(new MetricKey("foo", "loadSuccess"), Gauge.class).value()).isEqualTo(11);
        assertThat(metrics.metric(new MetricKey("foo", "loadFailure"), Gauge.class).value()).isEqualTo(12);
        assertThat(metrics.metric(new MetricKey("foo", "loadTotal"), Gauge.class).value()).isEqualTo(23);
        assertThat(metrics.metric(new MetricKey("foo", "loadDuration"), Gauge.class).value()).isEqualTo(13);
        assertThat(metrics.metric(new MetricKey("foo", "eviction"), Gauge.class).value()).isEqualTo(14);
        assertThat(metrics.metric(new MetricKey("foo", "evictionWeight"), Gauge.class).value()).isEqualTo(15);
        assertThat(metrics.metric(new MetricKey("foo", "estimatedSize"), Gauge.class).value()).isEqualTo(16);

        // Make sure Cache.stats() and estimatedSize() were called once more since the initial update.
        verify(cache, times(2)).stats();
        verify(cache, times(2)).estimatedSize();
    }

    private static class GaugeValueRequirement implements Consumer<Gauge> {
        private final long expectedValue;

        GaugeValueRequirement(long expectedValue) {
            this.expectedValue = expectedValue;
        }

        @Override
        public void accept(Gauge gauge) {
            assertThat(gauge.value()).isEqualTo(expectedValue);
        }
    }
}
