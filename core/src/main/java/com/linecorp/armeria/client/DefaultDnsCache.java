/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.internal.common.util.CollectionUtil.truncate;
import static java.util.Objects.requireNonNull;

import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;

final class DefaultDnsCache implements DnsCache {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDnsCache.class);

    private final List<DnsCacheListener> listeners = new CopyOnWriteArrayList<>();
    private final Cache<DnsQuestion, CacheEntry> cache;
    private final ScheduledExecutorService executor;
    private final int minTtl;
    private final int maxTtl;
    private final int negativeTtl;
    private boolean evictionWarned;

    DefaultDnsCache(String cacheSpec, MeterRegistry meterRegistry, ScheduledExecutorService executor,
                    int minTtl, int maxTtl, int negativeTtl) {
        this.executor = executor;
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
        this.negativeTtl = negativeTtl;
        cache = Caffeine.from(cacheSpec)
                        .removalListener((RemovalListener<DnsQuestion, CacheEntry>) (key, value, cause) -> {
                            if (value != null) {
                                value.scheduledFuture.cancel(true);
                            }

                            if (key == null || value == null) {
                                // A key or value could be null if collected.
                                return;
                            }

                            final boolean evicted = cause == RemovalCause.SIZE;
                            if (evicted) {
                                if (!evictionWarned) {
                                    evictionWarned = true;
                                    logger.warn(
                                            "{} is evicted due to size. Please consider increasing the " +
                                            "maximum size of the DNS cache. cache spec:" +
                                            " {}", key, cacheSpec);
                                } else {
                                    logger.debug("{} is evicted due to {}.", key, cause);
                                }
                            }

                            final UnknownHostException reason = value.cause();
                            final List<DnsRecord> records = value.records();
                            assert records != null || reason != null;
                            for (DnsCacheListener listener : listeners) {
                                invokeListener(listener, evicted, key, records, reason);
                            }
                        })
                        .executor(executor)
                        .build();

        final MeterIdPrefix idPrefix = new MeterIdPrefix("armeria.client.dns.cache");
        CaffeineMetricSupport.setup(meterRegistry, idPrefix, cache);
    }

    private static void invokeListener(DnsCacheListener listener, boolean evicted, DnsQuestion question,
                                       @Nullable List<DnsRecord> records,
                                       @Nullable UnknownHostException reason) {
        try {
            if (evicted) {
                listener.onEviction(question, records, reason);
            } else {
                listener.onRemoval(question, records, reason);
            }
        } catch (Exception ex) {
            logger.warn("Unexpected exception while invoking {}", listener, ex);
        }
    }

    @Override
    public void cache(DnsQuestion question, Iterable<? extends DnsRecord> records) {
        requireNonNull(question, "question");
        requireNonNull(records, "records");
        if (maxTtl == 0) {
            // DNS records cache is disabled.
            return;
        }

        final List<DnsRecord> copied = ImmutableList.copyOf(records);

        final long ttl = copied.stream()
                               .mapToLong(DnsRecord::timeToLive)
                               .min()
                               .orElse(minTtl);
        final int effectiveTtl = Math.min(maxTtl, Math.max(minTtl, Ints.saturatedCast(ttl)));

        cache.put(question, new CacheEntry(cache, question, copied, null, executor, effectiveTtl));
    }

    @Override
    public void cache(DnsQuestion question, UnknownHostException cause) {
        requireNonNull(question, "question");
        requireNonNull(cause, "cause");

        if (negativeTtl > 0) {
            cache.put(question, new CacheEntry(cache, question, null, cause, executor, negativeTtl));
        }
    }

    @Override
    public List<DnsRecord> get(DnsQuestion question) throws UnknownHostException {
        requireNonNull(question, "question");
        final CacheEntry entry = cache.getIfPresent(question);
        if (entry == null) {
            return null;
        }
        final UnknownHostException cause = entry.cause();
        if (cause != null) {
            throw cause;
        }
        return entry.records();
    }

    @Override
    public void remove(DnsQuestion question) {
        requireNonNull(question, "question");
        cache.invalidate(question);
    }

    @Override
    public void removeAll() {
        cache.invalidateAll();
    }

    @Override
    public void addListener(DnsCacheListener listener) {
        requireNonNull(listener, "listener");
        listeners.add(listener);
    }

    private static final class CacheEntry {

        @Nullable
        private final List<DnsRecord> records;
        @Nullable
        private final UnknownHostException cause;
        private final ScheduledFuture<?> scheduledFuture;
        int hashCode;

        CacheEntry(Cache<DnsQuestion, CacheEntry> cache, DnsQuestion question,
                   @Nullable List<DnsRecord> records,
                   @Nullable UnknownHostException cause, ScheduledExecutorService executor,
                   int timeToLive) {
            assert records != null || cause != null;
            this.records = records;
            this.cause = cause;

            scheduledFuture = executor.schedule(() -> {
                cache.asMap().remove(question, this);
            }, timeToLive, TimeUnit.SECONDS);
        }

        @Nullable
        List<DnsRecord> records() {
            return records;
        }

        @Nullable
        UnknownHostException cause() {
            return cause;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CacheEntry)) {
                return false;
            }
            final CacheEntry that = (CacheEntry) o;
            return Objects.equal(records, that.records) &&
                   Objects.equal(cause, that.cause) &&
                   scheduledFuture.equals(that.scheduledFuture);
        }

        @Override
        public int hashCode() {
            if (hashCode == 0) {
                int hashCode = scheduledFuture.hashCode();
                if (records != null) {
                    hashCode = hashCode * 31 + records.hashCode();
                }
                if (cause != null) {
                    hashCode = hashCode * 31 + cause.hashCode();
                }
                return this.hashCode = hashCode;
            }

            return hashCode;
        }

        @Override
        public String toString() {
            final ToStringHelper builder = MoreObjects.toStringHelper(this)
                                                      .omitNullValues()
                                                      .add("cause", cause)
                                                      .add("scheduledFuture", scheduledFuture);
            if (records != null) {
                builder.add("records", truncate(records, 10))
                       .add("numRecords", records.size());
            }
            return builder.toString();
        }
    }
}
