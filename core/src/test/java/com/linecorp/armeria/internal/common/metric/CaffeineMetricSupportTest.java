/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.common.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;

import io.micrometer.core.instrument.MeterRegistry;

public class CaffeineMetricSupportTest {

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private Policy<Object, Object> policy;

    @Before
    public void setUp() {
        when(policy.isRecordingStats()).thenReturn(true);
    }

    @Test
    public void test() {
        final MockLoadingCache cache = new MockLoadingCache(1, 2, 3, 4, 5, 6, 7, 8);
        final AtomicLong ticker = new AtomicLong();
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        CaffeineMetricSupport.setup(registry, new MeterIdPrefix("foo"), cache, ticker::get);
        assertThat(registry.getMeters()).isNotEmpty();

        assertThat(cache.statsCalls()).isOne();
        assertThat(cache.estimatedSizeCalls()).isOne();

        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("foo.requests#count{result=hit}", 1.0)
                .containsEntry("foo.requests#count{result=miss}", 2.0)
                .containsEntry("foo.loads#count{result=success}", 3.0)
                .containsEntry("foo.loads#count{result=failure}", 4.0)
                .containsEntry("foo.load.duration#count", 5.0)
                .containsEntry("foo.evictions#count", 6.0)
                .containsEntry("foo.eviction.weight#count", 7.0)
                .containsEntry("foo.estimated.size#value", 8.0);

        // Make sure Cache.stats() and estimatedSize() are not called since the initial update.
        assertThat(cache.statsCalls()).isOne();
        assertThat(cache.estimatedSizeCalls()).isOne();

        // Advance the ticker so that the next collection triggers stats() and estimatedSize().
        ticker.addAndGet(CaffeineMetricSupport.UPDATE_INTERVAL_NANOS);
        cache.update(9, 10, 11, 12, 13, 14, 15, 16);

        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("foo.requests#count{result=hit}", 9.0)
                .containsEntry("foo.requests#count{result=miss}", 10.0)
                .containsEntry("foo.loads#count{result=success}", 11.0)
                .containsEntry("foo.loads#count{result=failure}", 12.0)
                .containsEntry("foo.load.duration#count", 13.0)
                .containsEntry("foo.evictions#count", 14.0)
                .containsEntry("foo.eviction.weight#count", 15.0)
                .containsEntry("foo.estimated.size#value", 16.0);

        // Make sure Cache.stats() and estimatedSize() were called once more since the initial update.
        assertThat(cache.statsCalls()).isEqualTo(2);
        assertThat(cache.estimatedSizeCalls()).isEqualTo(2);
    }

    @Test
    public void testNonLoadingCache() {
        final MockCache cache = new MockCache(1, 2, 3, 4, 5);
        final AtomicLong ticker = new AtomicLong();
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        CaffeineMetricSupport.setup(registry, new MeterIdPrefix("bar"), cache, ticker::get);

        assertThat(cache.statsCalls()).isOne();
        assertThat(cache.estimatedSizeCalls()).isOne();

        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("bar.requests#count{result=hit}", 1.0)
                .containsEntry("bar.requests#count{result=miss}", 2.0)
                .containsEntry("bar.evictions#count", 3.0)
                .containsEntry("bar.eviction.weight#count", 4.0)
                .containsEntry("bar.estimated.size#value", 5.0);

        // Make sure the meters related with loading are not registered.
        assertThat(MoreMeters.measureAll(registry)).doesNotContainKeys(
                "bar.loads#count{result=success}",
                "bar.loads#count{result=failure}",
                "bar.load.duration#count");
    }

    @Test
    public void aggregation() {
        final MockLoadingCache cache1 = new MockLoadingCache(1, 2, 3, 4, 5, 6, 7, 8);
        final MockLoadingCache cache2 = new MockLoadingCache(9, 10, 11, 12, 13, 14, 15, 16);
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final MeterIdPrefix idPrefix = new MeterIdPrefix("baz");

        // Register two caches at the same meter ID.
        CaffeineMetricSupport.setup(registry, idPrefix, cache1);
        CaffeineMetricSupport.setup(registry, idPrefix, cache2);

        // .. and their stats are aggregated.
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("baz.requests#count{result=hit}", 10.0)
                .containsEntry("baz.requests#count{result=miss}", 12.0)
                .containsEntry("baz.loads#count{result=success}", 14.0)
                .containsEntry("baz.loads#count{result=failure}", 16.0)
                .containsEntry("baz.load.duration#count", 18.0)
                .containsEntry("baz.evictions#count", 20.0)
                .containsEntry("baz.eviction.weight#count", 22.0)
                .containsEntry("baz.estimated.size#value", 24.0);
    }

    @Test
    public void aggregationAfterGC() throws Exception {
        final MockCache cache1 = new MockCache(1, 2, 3, 4, 5);
        Object cache2 = new MockLoadingCache(6, 7, 8, 9, 10, 11, 12, 13);
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final AtomicLong ticker = new AtomicLong();
        final MeterIdPrefix idPrefix = new MeterIdPrefix("baz");

        // Register two caches at the same meter ID.
        CaffeineMetricSupport.setup(registry, idPrefix, cache1, ticker::get);
        CaffeineMetricSupport.setup(registry, idPrefix, (Cache<?, ?>) cache2, ticker::get);

        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("baz.requests#count{result=hit}", 7.0)
                .containsEntry("baz.requests#count{result=miss}", 9.0)
                .containsEntry("baz.loads#count{result=success}", 8.0)
                .containsEntry("baz.loads#count{result=failure}", 9.0)
                .containsEntry("baz.load.duration#count", 10.0)
                .containsEntry("baz.evictions#count", 14.0)
                .containsEntry("baz.eviction.weight#count", 16.0)
                .containsEntry("baz.estimated.size#value", 18.0);

        ticker.addAndGet(CaffeineMetricSupport.UPDATE_INTERVAL_NANOS);

        // Ensure the weak reference which held the cache is cleaned up.
        cache2 = new WeakReference<>(cache2);
        System.gc();
        Thread.sleep(1000);
        assertThat(((Reference<?>) cache2).get()).isNull();

        // Check if the counters are not decreased after the second cache is GC'd.
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("baz.requests#count{result=hit}", 7.0)
                .containsEntry("baz.requests#count{result=miss}", 9.0)
                .containsEntry("baz.loads#count{result=success}", 8.0)
                .containsEntry("baz.loads#count{result=failure}", 9.0)
                .containsEntry("baz.load.duration#count", 10.0)
                .containsEntry("baz.evictions#count", 14.0)
                .containsEntry("baz.eviction.weight#count", 16.0)
                .containsEntry("baz.estimated.size#value", 5.0); // .. except 'estimatedSize' which is a gauge
    }

    @Test
    public void sameCacheTwice() {
        final MockCache cache = new MockCache(1, 2, 3, 4, 5);
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final MeterIdPrefix idPrefix = new MeterIdPrefix("baz");

        // Register the same cache twice at the same meter ID.
        CaffeineMetricSupport.setup(registry, idPrefix, cache);
        CaffeineMetricSupport.setup(registry, idPrefix, cache);

        // .. and check if the stats are *not* doubled.
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry("baz.requests#count{result=hit}", 1.0)
                .containsEntry("baz.requests#count{result=miss}", 2.0)
                .containsEntry("baz.evictions#count", 3.0)
                .containsEntry("baz.eviction.weight#count", 4.0)
                .containsEntry("baz.estimated.size#value", 5.0);
    }

    @Test
    public void notRecording() {
        when(policy.isRecordingStats()).thenReturn(false);
        final MockLoadingCache cache = new MockLoadingCache(1, 2, 3, 4, 5, 6, 7, 8);
        final AtomicLong ticker = new AtomicLong();
        final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        CaffeineMetricSupport.setup(registry, new MeterIdPrefix("foo"), cache, ticker::get);
        assertThat(registry.getMeters()).isEmpty();
    }

    // Not using mocking framework because we need tight control over the life cycle of the mock object.
    private class MockCache implements Cache<Object, Object> {

        private CacheStats stats;
        private long estimatedSize;
        private int statsCalls;
        private int estimatedSizeCalls;

        MockCache(long hitCount, long missCount, long evictionCount, long evictionWeight, long estimatedSize) {
            update(hitCount, missCount, evictionCount, evictionWeight, estimatedSize);
        }

        MockCache(long hitCount, long missCount, long loadSuccessCount, long loadFailureCount,
                  long totalLoadTime, long evictionCount, long evictionWeight, long estimatedSize) {

            update(hitCount, missCount, loadSuccessCount, loadFailureCount,
                   totalLoadTime, evictionCount, evictionWeight, estimatedSize);
        }

        void update(long hitCount, long missCount,
                    long evictionCount, long evictionWeight, long estimatedSize) {

            update(hitCount, missCount, 0, 0, 0, evictionCount, evictionWeight, estimatedSize);
        }

        void update(long hitCount, long missCount, long loadSuccessCount, long loadFailureCount,
                    long totalLoadTime, long evictionCount, long evictionWeight, long estimatedSize) {
            stats = new CacheStats(hitCount, missCount, loadSuccessCount, loadFailureCount,
                                   totalLoadTime, evictionCount, evictionWeight);
            this.estimatedSize = estimatedSize;
        }

        int statsCalls() {
            return statsCalls;
        }

        int estimatedSizeCalls() {
            return estimatedSizeCalls;
        }

        @Nonnull
        @Override
        public CacheStats stats() {
            statsCalls++;
            return stats;
        }

        @Override
        public long estimatedSize() {
            estimatedSizeCalls++;
            return estimatedSize;
        }

        @Override
        public Object getIfPresent(@Nonnull Object key) {
            return reject();
        }

        @Override
        public Object get(@Nonnull Object key, @Nonnull Function<? super Object, ?> mappingFunction) {
            return reject();
        }

        @Nonnull
        @Override
        public Map<Object, Object> getAllPresent(@Nonnull Iterable<?> keys) {
            return reject();
        }

        @Override
        public void put(@Nonnull Object key, @Nonnull Object value) {
            reject();
        }

        @Override
        public void putAll(@Nonnull Map<?, ?> map) {
            reject();
        }

        @Override
        public void invalidate(@Nonnull Object key) {
            reject();
        }

        @Override
        public void invalidateAll(@Nonnull Iterable<?> keys) {
            reject();
        }

        @Override
        public void invalidateAll() {
            reject();
        }

        @Nonnull
        @Override
        public ConcurrentMap<Object, Object> asMap() {
            return reject();
        }

        @Override
        public void cleanUp() {
            reject();
        }

        @Nonnull
        @Override
        public Policy<Object, Object> policy() {
            return policy;
        }

        protected <T> T reject() {
            throw new UnsupportedOperationException();
        }
    }

    private final class MockLoadingCache extends MockCache implements LoadingCache<Object, Object> {

        MockLoadingCache(long hitCount, long missCount, long loadSuccessCount, long loadFailureCount,
                         long totalLoadTime, long evictionCount, long evictionWeight, long estimatedSize) {
            super(hitCount, missCount, loadSuccessCount, loadFailureCount,
                  totalLoadTime, evictionCount, evictionWeight, estimatedSize);
        }

        @CheckForNull
        @Override
        public Object get(@Nonnull Object key) {
            return reject();
        }

        @Nonnull
        @Override
        public Map<Object, Object> getAll(@Nonnull Iterable<?> keys) {
            return reject();
        }

        @Override
        public void refresh(@Nonnull Object key) {
            reject();
        }
    }
}
