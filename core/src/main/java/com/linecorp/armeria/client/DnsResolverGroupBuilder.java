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

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Duration;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.dns.AbstractDnsResolverBuilder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;

/**
 * Builds an {@link AddressResolverGroup} which builds {@link AddressResolver}s that update DNS caches
 * automatically. Standard {@link DnsNameResolver} will only expire a cache entry after TTL,
 * meaning DNS queries after TTL will always take time to resolve. A refreshing {@link AddressResolver}
 * on the other hand updates the DNS cache automatically when TTL elapses,
 * meaning DNS queries after TTL will retrieve a refreshed result right away. If refreshing fails,
 * the {@link AddressResolver} will retry with {@link #refreshBackoff(Backoff)}.
 *
 * <p>The refreshing {@link AddressResolver} will only start auto refresh for a given hostname
 * on the second access before TTL to avoid auto-refreshing for queries that only happen once
 * (e.g., requests during server startup).
 */
public final class DnsResolverGroupBuilder extends AbstractDnsResolverBuilder {

    private Backoff refreshBackoff = Backoff.ofDefault();

    @Nullable
    private ResolvedAddressTypes resolvedAddressTypes;

    DnsResolverGroupBuilder() {}

    /**
     * Sets {@link Backoff} which is used when the {@link DnsNameResolver} fails to update the cache.
     */
    public DnsResolverGroupBuilder refreshBackoff(Backoff refreshBackoff) {
        this.refreshBackoff = requireNonNull(refreshBackoff, "refreshBackoff");
        return this;
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

    @Nullable
    MeterRegistry meterRegistry0() {
        return meterRegistry();
    }

    RefreshingAddressResolverGroup build(EventLoopGroup eventLoopGroup) {
        return new RefreshingAddressResolverGroup(cacheSpec(), minTtl(), maxTtl(), negativeTtl(),
                                                  refreshBackoff, resolvedAddressTypes,
                                                  dnsResolverFactory(eventLoopGroup)
        );
    }

    // Override the return type of the chaining methods in the super class.

    @Override
    public DnsResolverGroupBuilder traceEnabled(boolean traceEnabled) {
        return (DnsResolverGroupBuilder) super.traceEnabled(traceEnabled);
    }

    @Override
    public DnsResolverGroupBuilder queryTimeout(Duration queryTimeout) {
        return (DnsResolverGroupBuilder) super.queryTimeout(queryTimeout);
    }

    @Override
    public DnsResolverGroupBuilder queryTimeoutMillis(long queryTimeoutMillis) {
        return (DnsResolverGroupBuilder) super.queryTimeoutMillis(queryTimeoutMillis);
    }

    @Override
    public DnsResolverGroupBuilder queryTimeoutForEachAttempt(Duration queryTimeoutForEachAttempt) {
        return (DnsResolverGroupBuilder) super.queryTimeoutForEachAttempt(queryTimeoutForEachAttempt);
    }

    @Override
    public DnsResolverGroupBuilder queryTimeoutMillisForEachAttempt(long queryTimeoutMillisForEachAttempt) {
        return (DnsResolverGroupBuilder) super.queryTimeoutMillisForEachAttempt(
                queryTimeoutMillisForEachAttempt);
    }

    @Override
    public DnsResolverGroupBuilder recursionDesired(boolean recursionDesired) {
        return (DnsResolverGroupBuilder) super.recursionDesired(recursionDesired);
    }

    @Override
    public DnsResolverGroupBuilder maxQueriesPerResolve(int maxQueriesPerResolve) {
        return (DnsResolverGroupBuilder) super.maxQueriesPerResolve(maxQueriesPerResolve);
    }

    @Override
    public DnsResolverGroupBuilder serverAddresses(InetSocketAddress... serverAddresses) {
        return (DnsResolverGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsResolverGroupBuilder serverAddresses(Iterable<InetSocketAddress> serverAddresses) {
        return (DnsResolverGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsResolverGroupBuilder serverAddressStreamProvider(
            DnsServerAddressStreamProvider serverAddressStreamProvider) {
        return (DnsResolverGroupBuilder) super.serverAddressStreamProvider(serverAddressStreamProvider);
    }

    @Deprecated
    @Override
    public DnsResolverGroupBuilder dnsServerAddressStreamProvider(
            DnsServerAddressStreamProvider dnsServerAddressStreamProvider) {
        return (DnsResolverGroupBuilder) super.dnsServerAddressStreamProvider(dnsServerAddressStreamProvider);
    }

    @Override
    public DnsResolverGroupBuilder maxPayloadSize(int maxPayloadSize) {
        return (DnsResolverGroupBuilder) super.maxPayloadSize(maxPayloadSize);
    }

    @Override
    public DnsResolverGroupBuilder optResourceEnabled(boolean optResourceEnabled) {
        return (DnsResolverGroupBuilder) super.optResourceEnabled(optResourceEnabled);
    }

    @Override
    public DnsResolverGroupBuilder hostsFileEntriesResolver(
            HostsFileEntriesResolver hostsFileEntriesResolver) {
        return (DnsResolverGroupBuilder) super.hostsFileEntriesResolver(hostsFileEntriesResolver);
    }

    @Override
    public DnsResolverGroupBuilder dnsQueryLifecycleObserverFactory(
            DnsQueryLifecycleObserverFactory observerFactory) {
        return (DnsResolverGroupBuilder) super.dnsQueryLifecycleObserverFactory(observerFactory);
    }

    @Deprecated
    @Override
    public DnsResolverGroupBuilder disableDnsQueryMetrics() {
        return (DnsResolverGroupBuilder) super.disableDnsQueryMetrics();
    }

    @Override
    public DnsResolverGroupBuilder searchDomains(String... searchDomains) {
        return (DnsResolverGroupBuilder) super.searchDomains(searchDomains);
    }

    @Override
    public DnsResolverGroupBuilder searchDomains(Iterable<String> searchDomains) {
        return (DnsResolverGroupBuilder) super.searchDomains(searchDomains);
    }

    @Override
    public DnsResolverGroupBuilder ndots(int ndots) {
        return (DnsResolverGroupBuilder) super.ndots(ndots);
    }

    @Override
    public DnsResolverGroupBuilder decodeIdn(boolean decodeIdn) {
        return (DnsResolverGroupBuilder) super.decodeIdn(decodeIdn);
    }

    @Override
    public DnsResolverGroupBuilder meterRegistry(MeterRegistry meterRegistry) {
        return (DnsResolverGroupBuilder) super.meterRegistry(meterRegistry);
    }

    @Override
    public DnsResolverGroupBuilder cacheSpec(String cacheSpec) {
        return (DnsResolverGroupBuilder) super.cacheSpec(cacheSpec);
    }

    @Override
    public DnsResolverGroupBuilder ttl(int minTtl, int maxTtl) {
        return (DnsResolverGroupBuilder) super.ttl(minTtl, maxTtl);
    }

    @Override
    public DnsResolverGroupBuilder negativeTtl(int negativeTtl) {
        return (DnsResolverGroupBuilder) super.negativeTtl(negativeTtl);
    }

    @Override
    public DnsResolverGroupBuilder dnsCache(DnsCache dnsCache) {
        return (DnsResolverGroupBuilder) super.dnsCache(dnsCache);
    }
}
