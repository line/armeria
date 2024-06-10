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

package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.ToLongFunction;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;

/**
 * Builds an {@link AddressResolverGroup} which builds {@link AddressResolver}s that update DNS caches
 * automatically. Standard {@link DnsNameResolver} will only expire a cache entry after TTL,
 * meaning DNS queries after TTL will always take time to resolve. A refreshing {@link AddressResolver}
 * on the other hand updates the DNS cache automatically when TTL elapses,
 * meaning DNS queries after TTL will retrieve a refreshed result right away. If refreshing fails,
 * the {@link AddressResolver} will retry with {@link #autoRefreshBackoff(Backoff)}.
 */
public final class DnsResolverGroupBuilder extends AbstractDnsResolverBuilder<DnsResolverGroupBuilder> {

    static final ToLongFunction<String> DEFAULT_AUTO_REFRESH_TIMEOUT_FUNCTION = hostname -> Long.MAX_VALUE;

    @Nullable
    private ResolvedAddressTypes resolvedAddressTypes;

    // Auto refresh is enabled by default
    private boolean autoRefresh = true;
    @Nullable
    private Backoff autoRefreshBackoff;
    @Nullable
    private ToLongFunction<String> autoRefreshTimeoutFunction;

    DnsResolverGroupBuilder() {}

    /**
     * Sets {@link Backoff} which is used when the {@link DnsNameResolver} fails to update the cache.
     *
     * @deprecated Use {@link #autoRefreshBackoff(Backoff)} instead.
     */
    @Deprecated
    public DnsResolverGroupBuilder refreshBackoff(Backoff refreshBackoff) {
        return autoRefreshBackoff(refreshBackoff);
    }

    /**
     * Sets {@link ResolvedAddressTypes} which is the list of the protocol families of the address resolved.
     *
     * @see DnsNameResolverBuilder#resolvedAddressTypes(ResolvedAddressTypes)
     */
    public DnsResolverGroupBuilder resolvedAddressTypes(
            ResolvedAddressTypes resolvedAddressTypes) {
        this.resolvedAddressTypes = requireNonNull(resolvedAddressTypes, "resolvedAddressTypes");
        return this;
    }

    /**
     * Sets whether to enable auto refresh for expired {@link DnsRecord}s.
     * This option is enabled by default.
     *
     * <p>If this option is disabled, the expired {@link DnsRecord} is removed from the {@link DnsCache} and a
     * new {@link DnsQuery} will be executed when the next request to the domain is made.
     */
    @UnstableApi
    public DnsResolverGroupBuilder enableAutoRefresh(boolean autoRefresh) {
        this.autoRefresh = autoRefresh;
        return this;
    }

    /**
     * Sets the {@link Backoff} which is used when the {@link DnsNameResolver} fails to update the cache.
     */
    public DnsResolverGroupBuilder autoRefreshBackoff(Backoff refreshBackoff) {
        autoRefreshBackoff = requireNonNull(refreshBackoff, "refreshBackoff");
        return this;
    }

    /**
     * Sets the {@link ToLongFunction} which determines how long the {@link DnsRecord}s for a hostname should
     * be refreshed after the first cache. The {@link ToLongFunction} should return the refresh timeout in
     * milliseconds. If a non-positive number is returned, the {@link DnsRecord}s are removed immediately
     * without refresh.
     *
     * <p>Note that the timeout function is called whenever the cached {@link DnsRecord}s expire.
     *
     * <p>For example:
     * <pre>{@code
     * AtomicInteger refreshCounter = new AtomicInteger(MAX_NUM_REFRESH);
     * dnsResolverGroupBuilder.autoRefreshTimeout(hostname -> {
     *     if (hostname.endsWith("busy.domain.com")) {
     *         return Duration.ofDays(7).toMillis(); // Automatically refresh the cached domain for 7 days.
     *     }
     *     if (hostname.endsWith("sporadic.domain.dom")) {
     *         return 0; // Don't need to refresh a sporadically used domain.
     *     }
     *
     *     if (hostname.endsWith("counting.domain.dom")) {
     *         // Allow refreshing up to MAX_NUM_REFRESH times.
     *         if (refreshCounter.getAndDecrease() > 0) {
     *             return Long.MAX_VALUE;
     *         } else {
     *             refreshCounter.set(MAX_NUM_REFRESH);
     *             return 0;
     *         }
     *     }
     *     ...
     * });
     * }</pre>
     */
    @UnstableApi
    public DnsResolverGroupBuilder autoRefreshTimeout(ToLongFunction<? super String> timeoutFunction) {
        requireNonNull(timeoutFunction, "timeoutFunction");
        //noinspection unchecked
        autoRefreshTimeoutFunction = (ToLongFunction<String>) timeoutFunction;
        return this;
    }

    /**
     * Sets the timeout after which a refreshing {@link DnsRecord} should expire.
     * If this option is unspecified and {@link #enableAutoRefresh(boolean)} is set to
     * {@code true}, a cached {@link DnsRecord} is automatically refreshed until the {@link ClientFactory}
     * is closed. {@link Duration#ZERO} disables the timeout.
     */
    @UnstableApi
    public DnsResolverGroupBuilder autoRefreshTimeout(Duration timeout) {
        requireNonNull(timeout, "timeout");
        return autoRefreshTimeoutMillis(timeout.toMillis());
    }

    /**
     * Sets the timeout in milliseconds after which a refreshing {@link DnsRecord} should expire.
     * If this option is unspecified and {@link #enableAutoRefresh(boolean)} is set to
     * {@code true}, a cached {@link DnsRecord} is automatically refreshed until the {@link ClientFactory}
     * is closed. {@code 0} disables the timeout.
     */
    @UnstableApi
    public DnsResolverGroupBuilder autoRefreshTimeoutMillis(long timeoutMillis) {
        checkArgument(timeoutMillis >= 0, "timeoutMillis: %s (expected: >= 0)", timeoutMillis);
        final long adjustedTimeoutMillis;
        if (timeoutMillis == 0) {
            adjustedTimeoutMillis = Long.MAX_VALUE;
        } else {
            adjustedTimeoutMillis = timeoutMillis;
        }
        return autoRefreshTimeout(hostname -> adjustedTimeoutMillis);
    }

    @Nullable
    MeterRegistry meterRegistry0() {
        return meterRegistry();
    }

    RefreshingAddressResolverGroup build(EventLoopGroup eventLoopGroup) {
        ToLongFunction<String> autoRefreshTimeoutFunction = this.autoRefreshTimeoutFunction;
        Backoff autoRefreshBackoff = this.autoRefreshBackoff;
        if (!autoRefresh && (autoRefreshTimeoutFunction != null || autoRefreshBackoff != null)) {
            throw new IllegalStateException("Can't set 'autoRefreshTimeout()' or 'autoRefreshBackoff()' " +
                                            "if 'autoRefresh' is disabled");
        }

        if (autoRefresh) {
            if (autoRefreshTimeoutFunction == null) {
                autoRefreshTimeoutFunction = DEFAULT_AUTO_REFRESH_TIMEOUT_FUNCTION;
            }
            if (autoRefreshBackoff == null) {
                autoRefreshBackoff = Backoff.ofDefault();
            }
        }
        return new RefreshingAddressResolverGroup(cacheSpec(), negativeTtl(), resolvedAddressTypes,
                                                  maybeCreateDnsCache(), autoRefresh,
                                                  autoRefreshBackoff, autoRefreshTimeoutFunction,
                                                  searchDomains(), ndots(), queryTimeoutMillis(),
                                                  hostsFileEntriesResolver(),
                                                  buildConfigurator(eventLoopGroup));
    }
}
