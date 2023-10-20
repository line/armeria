/*
 *  Copyright 2019 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.client.RefreshingAddressResolver.CacheEntry;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.client.dns.DefaultDnsResolver;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.EventExecutor;

/**
 * Creates and manages refreshing {@link AddressResolver}s and the DNS cache.
 */
final class RefreshingAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    // Forked from Netty 4.1.43 at 2e5dd288008d4e674f53beaf8d323595813062fb
    // - if else logic in static initialization block
    // - anyInterfaceSupportsIpV6()

    private static final List<DnsRecordType> defaultDnsRecordTypes;

    static {
        final ResolvedAddressTypes resolvedAddressTypes;
        if (SystemInfo.hasIpV6()) {
            if (NetUtil.isIpV6AddressesPreferred()) {
                resolvedAddressTypes = ResolvedAddressTypes.IPV6_PREFERRED;
            } else {
                resolvedAddressTypes = ResolvedAddressTypes.IPV4_PREFERRED;
            }
        } else {
            resolvedAddressTypes = ResolvedAddressTypes.IPV4_ONLY;
        }

        defaultDnsRecordTypes = dnsRecordTypes(resolvedAddressTypes);
    }

    private static ImmutableList<DnsRecordType> dnsRecordTypes(ResolvedAddressTypes resolvedAddressTypes) {
        final Builder<DnsRecordType> builder = ImmutableList.builder();
        switch (resolvedAddressTypes) {
            case IPV4_ONLY:
                builder.add(DnsRecordType.A);
                break;
            case IPV6_ONLY:
                builder.add(DnsRecordType.AAAA);
                break;
            case IPV4_PREFERRED:
                builder.add(DnsRecordType.A);
                builder.add(DnsRecordType.AAAA);
                break;
            case IPV6_PREFERRED:
                builder.add(DnsRecordType.AAAA);
                builder.add(DnsRecordType.A);
                break;
        }
        return builder.build();
    }

    private final int negativeTtl;

    private final List<DnsRecordType> dnsRecordTypes;
    private final DnsCache dnsResolverCache;

    private final boolean autoRefresh;
    @Nullable
    private final Backoff autoRefreshBackoff;
    @Nullable
    private final ToLongFunction<String> autoRefreshTimeoutFunction;

    private final List<String> searchDomains;
    private final int ndots;
    private final long queryTimeoutMillis;
    private final HostsFileEntriesResolver hostsFileEntriesResolver;
    private final Cache<String, CacheEntry> addressResolverCache;
    private final Consumer<DnsNameResolverBuilder> resolverConfigurator;

    @Nullable
    private final ResolvedAddressTypes resolvedAddressTypes;

    RefreshingAddressResolverGroup(
            String cacheSpec, int negativeTtl, @Nullable ResolvedAddressTypes resolvedAddressTypes,
            DnsCache dnsResolverCache, boolean autoRefresh, @Nullable Backoff autoRefreshBackoff,
            @Nullable ToLongFunction<String> autoRefreshTimeoutFunction,
            List<String> searchDomains, int ndots, long queryTimeoutMillis,
            HostsFileEntriesResolver hostsFileEntriesResolver,
            Consumer<DnsNameResolverBuilder> resolverConfigurator) {
        this.negativeTtl = negativeTtl;
        if (resolvedAddressTypes == null) {
            dnsRecordTypes = defaultDnsRecordTypes;
        } else {
            dnsRecordTypes = dnsRecordTypes(resolvedAddressTypes);
        }
        this.dnsResolverCache = dnsResolverCache;
        this.autoRefresh = autoRefresh;
        this.autoRefreshBackoff = autoRefreshBackoff;
        this.autoRefreshTimeoutFunction = autoRefreshTimeoutFunction;
        this.searchDomains = searchDomains;
        this.ndots = ndots;
        this.queryTimeoutMillis = queryTimeoutMillis;
        this.hostsFileEntriesResolver = hostsFileEntriesResolver;
        addressResolverCache = buildCache(cacheSpec);
        this.resolverConfigurator = resolverConfigurator;
        this.resolvedAddressTypes = resolvedAddressTypes;
    }

    @VisibleForTesting
    Cache<String, CacheEntry> cache() {
        return addressResolverCache;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) throws Exception {
        assert executor instanceof EventLoop;
        final EventLoop eventLoop = (EventLoop) executor;
        final DnsNameResolverBuilder builder = new DnsNameResolverBuilder(eventLoop);
        if (resolvedAddressTypes != null) {
            builder.resolvedAddressTypes(resolvedAddressTypes);
        }
        resolverConfigurator.accept(builder);
        final DefaultDnsResolver resolver = DefaultDnsResolver.of(builder.build(), dnsResolverCache, executor,
                                                                  searchDomains, ndots, queryTimeoutMillis,
                                                                  hostsFileEntriesResolver);
        return new RefreshingAddressResolver(eventLoop, resolver, dnsRecordTypes, addressResolverCache,
                                             dnsResolverCache, negativeTtl, autoRefresh,
                                             autoRefreshBackoff, autoRefreshTimeoutFunction);
    }

    @Override
    public void close() {
        super.close();
        addressResolverCache.invalidateAll();
    }

    private static Cache<String, CacheEntry> buildCache(String cacheSpec) {
        final Caffeine<Object, Object> b = Caffeine.from(cacheSpec);
        b.removalListener((RemovalListener<String, CacheEntry>) (key, value, cause) -> {
            if (value != null) {
                value.clear();
            }
        });
        return b.build();
    }
}
