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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.CaffeineSpec;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * A builder for {@link DnsCache}.
 */
@UnstableApi
public final class DnsCacheBuilder {

    static final DnsCache DEFAULT_CACHE = DnsCache.builder().build();

    private String cacheSpec = Flags.dnsCacheSpec();
    private MeterRegistry meterRegistry = Metrics.globalRegistry;
    private int minTtl = 1;
    private int maxTtl = Integer.MAX_VALUE;
    private int negativeTtl;

    DnsCacheBuilder() {}

    /**
     * Sets the {@linkplain CaffeineSpec Caffeine specification string}.
     * If not specified, {@link Flags#dnsCacheSpec()} is used by default.
     */
    public DnsCacheBuilder cacheSpec(String cacheSpec) {
        this.cacheSpec = cacheSpec;
        return this;
    }

    /**
     * Sets the {@link MeterRegistry} that collects cache stats.
     * If unspecified, {@link Metrics#globalRegistry} is used.
     */
    public DnsCacheBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        return this;
    }

    /**
     * Sets the minimum and maximum TTL of the cached DNS resource records in seconds. If the TTL of the DNS
     * resource record returned by the DNS server is less than the minimum TTL or greater than the maximum TTL,
     * this resolver will ignore the TTL from the DNS server and use the minimum TTL or the maximum TTL instead
     * respectively.
     * The default value is {@code 1} and {@link Integer#MAX_VALUE}, which practically tells this resolver to
     * respect the TTL from the DNS server.
     */
    public DnsCacheBuilder ttl(int minTtl, int maxTtl) {
        checkArgument(minTtl > 0 && minTtl <= maxTtl,
                      "minTtl: %s, maxTtl: %s (expected: 1 <= minTtl <= maxTtl)", minTtl, maxTtl);
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
        return this;
    }

    /**
     * Sets the TTL of the cache for the failed DNS queries in seconds. The default value is {@code 0} which
     * means that failed DNS queries are not cached.
     */
    public DnsCacheBuilder negativeTtl(int negativeTtl) {
        checkArgument(negativeTtl >= 0, "negativeTtl: %s (expected: >= 0)", negativeTtl);
        this.negativeTtl = negativeTtl;
        return this;
    }

    /**
     * Returns a newly created {@link DnsCache}.
     */
    public DnsCache build() {
        return new DefaultDnsCache(cacheSpec, meterRegistry, minTtl, maxTtl, negativeTtl);
    }
}
