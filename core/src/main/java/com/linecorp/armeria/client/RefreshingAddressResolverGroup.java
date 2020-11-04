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

import static com.linecorp.armeria.internal.client.DnsUtil.anyInterfaceSupportsIpV6;

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.client.RefreshingAddressResolver.CacheEntry;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.client.DefaultDnsNameResolver;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
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
        if (NetUtil.isIpV4StackPreferred() || !anyInterfaceSupportsIpV6()) {
            resolvedAddressTypes = ResolvedAddressTypes.IPV4_ONLY;
        } else {
            if (NetUtil.isIpV6AddressesPreferred()) {
                resolvedAddressTypes = ResolvedAddressTypes.IPV6_PREFERRED;
            } else {
                resolvedAddressTypes = ResolvedAddressTypes.IPV4_PREFERRED;
            }
        }

        defaultDnsRecordTypes = dnsRecordTypes(resolvedAddressTypes);
    }

    private static ImmutableList<DnsRecordType> dnsRecordTypes(ResolvedAddressTypes resolvedAddressTypes) {
        final Builder<DnsRecordType> builder = ImmutableList.builder();
        switch (resolvedAddressTypes) {
            case IPV4_ONLY:
                builder.add(DnsRecordType.A);
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

    private final ConcurrentMap<String, CompletableFuture<CacheEntry>> cache = new ConcurrentHashMap<>();

    private final int minTtl;
    private final int maxTtl;
    private final int negativeTtl;
    private final long queryTimeoutMillis;
    private final Backoff refreshBackoff;
    private final List<DnsRecordType> dnsRecordTypes;
    private final Consumer<DnsNameResolverBuilder> resolverConfigurator;
    private final MeterRegistry meterRegistry;

    RefreshingAddressResolverGroup(Consumer<DnsNameResolverBuilder> resolverConfigurator,
                                   int minTtl, int maxTtl, int negativeTtl, long queryTimeoutMillis,
                                   Backoff refreshBackoff,
                                   @Nullable ResolvedAddressTypes resolvedAddressTypes,
                                   MeterRegistry meterRegistry) {
        this.resolverConfigurator = resolverConfigurator;
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
        this.negativeTtl = negativeTtl;
        this.queryTimeoutMillis = queryTimeoutMillis;
        this.refreshBackoff = refreshBackoff;
        if (resolvedAddressTypes == null) {
            dnsRecordTypes = defaultDnsRecordTypes;
        } else {
            dnsRecordTypes = dnsRecordTypes(resolvedAddressTypes);
        }

        this.meterRegistry = meterRegistry;
    }

    @VisibleForTesting
    ConcurrentMap<String, CompletableFuture<CacheEntry>> cache() {
        return cache;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) throws Exception {
        assert executor instanceof EventLoop;
        final EventLoop eventLoop = (EventLoop) executor;
        final DnsNameResolverBuilder builder = new DnsNameResolverBuilder(eventLoop);
        builder.dnsQueryLifecycleObserverFactory(
                new DefaultDnsQueryLifecycleObserverFactory(meterRegistry,
                new MeterIdPrefix("armeria.client.dns.queries")));
        resolverConfigurator.accept(builder);
        final DefaultDnsNameResolver resolver = new DefaultDnsNameResolver(builder.build(), eventLoop,
                                                                           queryTimeoutMillis);
        return new RefreshingAddressResolver(eventLoop, cache, resolver, dnsRecordTypes, minTtl, maxTtl,
                                             negativeTtl, refreshBackoff);
    }

    @Override
    public void close() {
        super.close();
        while (!cache.isEmpty()) {
            for (final Iterator<Entry<String, CompletableFuture<CacheEntry>>> i = cache.entrySet().iterator();
                 i.hasNext();) {
                final Entry<String, CompletableFuture<CacheEntry>> entry = i.next();
                i.remove();
                entry.getValue().handle((cacheEntry, cause) -> {
                    cacheEntry.clear();
                    return null;
                });
            }
        }
    }
}
