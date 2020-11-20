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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.util.TransportType;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.BiDnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.NoopAuthoritativeDnsServerCache;
import io.netty.resolver.dns.NoopDnsCache;
import io.netty.resolver.dns.NoopDnsCnameCache;

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
public final class DnsResolverGroupBuilder {

    private Backoff refreshBackoff = Backoff.ofDefault();

    private int minTtl = 1;
    private int maxTtl = Integer.MAX_VALUE;
    private int negativeTtl;

    // DnsNameResolverBuilder properties:

    private boolean traceEnabled = true;
    private long queryTimeoutMillis = 5000; // 5 seconds.

    @Nullable
    private ResolvedAddressTypes resolvedAddressTypes;
    @Nullable
    private Boolean recursionDesired;
    @Nullable
    private Integer maxQueriesPerResolve;
    @Nullable
    private Integer maxPayloadSize;
    @Nullable
    private Boolean optResourceEnabled;
    @Nullable
    private HostsFileEntriesResolver hostsFileEntriesResolver;
    @Nullable
    private DnsServerAddressStreamProvider dnsServerAddressStreamProvider;
    @Nullable
    private DnsQueryLifecycleObserverFactory dnsQueryLifecycleObserverFactory;
    @Nullable
    private List<String> searchDomains;
    @Nullable
    private Integer ndots;
    @Nullable
    private Boolean decodeIdn;
    @Nullable
    private String cacheSpec;
    @Nullable
    private MeterRegistry meterRegistry;

    DnsResolverGroupBuilder() {}

    /**
     * Sets {@link Backoff} which is used when the {@link DnsNameResolver} fails to update the cache.
     */
    public DnsResolverGroupBuilder refreshBackoff(Backoff refreshBackoff) {
        this.refreshBackoff = requireNonNull(refreshBackoff, "refreshBackoff");
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
    public DnsResolverGroupBuilder ttl(int minTtl, int maxTtl) {
        checkArgument(minTtl > 0 && minTtl <= maxTtl,
                      "minTtl: %s, maxTtl: %s (expected: 1 <= minTtl <= maxTtl)", minTtl, maxTtl);
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
        return this;
    }

    /**
     * Sets the TTL of the cache for the failed DNS queries in seconds. The default value is {@code 0} which
     * means that the {@link AddressResolver} does not cache when DNS queries are failed.
     */
    public DnsResolverGroupBuilder negativeTtl(int negativeTtl) {
        checkArgument(negativeTtl >= 0, "negativeTtl: %s, (expected: >= 0)", negativeTtl);
        this.negativeTtl = negativeTtl;
        return this;
    }

    /**
     * Sets if this resolver should generate detailed trace information in exception messages so that
     * it is easier to understand the cause of resolution failure. This flag is enabled by default.
     */
    public DnsResolverGroupBuilder traceEnabled(boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
        return this;
    }

    /**
     * Sets the timeout of the DNS query performed by this resolver. {@code 0} disables the timeout.
     *
     * @see DnsNameResolverBuilder#queryTimeoutMillis(long)
     */
    public DnsResolverGroupBuilder queryTimeout(Duration queryTimeout) {
        requireNonNull(queryTimeout, "queryTimeout");
        checkArgument(!queryTimeout.isNegative(), "queryTimeout: %s (expected: >= 0)", queryTimeout);
        return queryTimeoutMillis(queryTimeout.toMillis());
    }

    /**
     * Sets the timeout of the DNS query performed by this resolver in milliseconds.
     * {@code 0} disables the timeout.
     *
     * @see DnsNameResolverBuilder#queryTimeoutMillis(long)
     */
    public DnsResolverGroupBuilder queryTimeoutMillis(long queryTimeoutMillis) {
        checkArgument(queryTimeoutMillis >= 0, "queryTimeoutMillis: %s (expected: >= 0)", queryTimeoutMillis);
        this.queryTimeoutMillis = queryTimeoutMillis;
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

    /**
     * Sets if this resolver has to send a DNS query with the RD (recursion desired) flag set.
     *
     * @see DnsNameResolverBuilder#recursionDesired(boolean)
     */
    public DnsResolverGroupBuilder recursionDesired(boolean recursionDesired) {
        this.recursionDesired = recursionDesired;
        return this;
    }

    /**
     * Returns the maximum allowed number of DNS queries to send when resolving a host name.
     *
     * @see DnsNameResolverBuilder#maxQueriesPerResolve(int)
     */
    public DnsResolverGroupBuilder maxQueriesPerResolve(int maxQueriesPerResolve) {
        checkArgument(maxQueriesPerResolve > 0,
                      "maxQueriesPerResolve: %s (expected: > 0)", maxQueriesPerResolve);
        this.maxQueriesPerResolve = maxQueriesPerResolve;
        return this;
    }

    /**
     * Sets the capacity of the datagram packet buffer in bytes.
     *
     * @see DnsNameResolverBuilder#maxPayloadSize(int)
     */
    public DnsResolverGroupBuilder maxPayloadSize(int maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
        return this;
    }

    /**
     * Enables the automatic inclusion of a optional records that tries to give the remote DNS server a hint
     * about how much data the resolver can read per response. Some DNSServer may not support this and so
     * fail to answer queries.
     *
     * @see DnsNameResolverBuilder#optResourceEnabled(boolean)
     */
    public DnsResolverGroupBuilder optResourceEnabled(boolean optResourceEnabled) {
        this.optResourceEnabled = optResourceEnabled;
        return this;
    }

    /**
     * Sets {@link HostsFileEntriesResolver} which is used to first check if the hostname is locally aliased.
     *
     * @see DnsNameResolverBuilder#hostsFileEntriesResolver(HostsFileEntriesResolver)
     */
    public DnsResolverGroupBuilder hostsFileEntriesResolver(
            HostsFileEntriesResolver hostsFileEntriesResolver) {
        this.hostsFileEntriesResolver = requireNonNull(hostsFileEntriesResolver, "hostsFileEntriesResolver");
        return this;
    }

    /**
     * Sets {@link DnsServerAddressStreamProvider} which is used to determine which DNS server is used to
     * resolve each hostname.
     *
     * @see DnsNameResolverBuilder#nameServerProvider(DnsServerAddressStreamProvider)
     */
    public DnsResolverGroupBuilder dnsServerAddressStreamProvider(
            DnsServerAddressStreamProvider dnsServerAddressStreamProvider) {
        this.dnsServerAddressStreamProvider =
                requireNonNull(dnsServerAddressStreamProvider, "dnsServerAddressStreamProvider");
        return this;
    }

    /**
     * Sets {@link DnsQueryLifecycleObserverFactory} that is used to generate objects which can observe
     * individual DNS queries.
     *
     * @see DnsNameResolverBuilder#dnsQueryLifecycleObserverFactory(DnsQueryLifecycleObserverFactory)
     */
    public DnsResolverGroupBuilder dnsQueryLifecycleObserverFactory(
            DnsQueryLifecycleObserverFactory dnsQueryLifecycleObserverFactory) {
        this.dnsQueryLifecycleObserverFactory =
                requireNonNull(dnsQueryLifecycleObserverFactory, "dnsQueryLifecycleObserverFactory");
        return this;
    }

    /**
     * Sets the list of search domains of the resolver.
     *
     * @see DnsNameResolverBuilder#searchDomains(Iterable)
     */
    public DnsResolverGroupBuilder searchDomains(Iterable<String> searchDomains) {
        this.searchDomains = ImmutableList.copyOf(requireNonNull(searchDomains, "searchDomains"));
        return this;
    }

    /**
     * Sets the search domains of the resolver.
     *
     * @see DnsNameResolverBuilder#searchDomains(Iterable)
     */
    public DnsResolverGroupBuilder searchDomains(String... searchDomains) {
        return searchDomains(ImmutableList.copyOf(requireNonNull(searchDomains, "searchDomains")));
    }

    /**
     * Sets the number of dots which must appear in a name before an initial absolute query is made.
     *
     * @see DnsNameResolverBuilder#ndots(int)
     */
    public DnsResolverGroupBuilder ndots(int ndots) {
        checkArgument(ndots >= 0, "ndots: %s (expected: >= 0)", ndots);
        this.ndots = ndots;
        return this;
    }

    /**
     * Sets if the domain and host names should be decoded to unicode when received.
     * See <a href="https://tools.ietf.org/html/rfc3492">rfc3492</a>. This flag is enabled by default.
     *
     * @see DnsNameResolverBuilder#decodeIdn(boolean)
     */
    public DnsResolverGroupBuilder decodeIdn(boolean decodeIdn) {
        this.decodeIdn = decodeIdn;
        return this;
    }

    /**
     * Sets {@link MeterRegistry} to collect the DNS query metrics.
     */
    DnsResolverGroupBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return this;
    }

    /**
     * Sets the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the domain
     * names and their resolved addresses. If not set, {@link Flags#dnsCacheSpec()} is used by default.
     */
    public DnsResolverGroupBuilder cacheSpec(String cacheSpec) {
        this.cacheSpec = requireNonNull(cacheSpec, "cacheSpec");
        return this;
    }

    RefreshingAddressResolverGroup build(EventLoopGroup eventLoopGroup) {
        final Consumer<DnsNameResolverBuilder> resolverConfigurator = builder -> {
            builder.channelType(TransportType.datagramChannelType(eventLoopGroup))
                   .socketChannelType(TransportType.socketChannelType(eventLoopGroup))
                   .resolveCache(NoopDnsCache.INSTANCE)
                   .authoritativeDnsServerCache(NoopAuthoritativeDnsServerCache.INSTANCE)
                   .cnameCache(NoopDnsCnameCache.INSTANCE)
                   .traceEnabled(traceEnabled)
                   .completeOncePreferredResolved(true);

            if (queryTimeoutMillis == 0) {
                builder.queryTimeoutMillis(Long.MAX_VALUE);
            } else {
                builder.queryTimeoutMillis(queryTimeoutMillis);
            }

            if (resolvedAddressTypes != null) {
                builder.resolvedAddressTypes(resolvedAddressTypes);
            }
            if (recursionDesired != null) {
                builder.recursionDesired(recursionDesired);
            }
            if (maxQueriesPerResolve != null) {
                builder.maxQueriesPerResolve(maxQueriesPerResolve);
            }
            if (maxPayloadSize != null) {
                builder.maxPayloadSize(maxPayloadSize);
            }
            if (optResourceEnabled != null) {
                builder.optResourceEnabled(optResourceEnabled);
            }
            if (hostsFileEntriesResolver != null) {
                builder.hostsFileEntriesResolver(hostsFileEntriesResolver);
            }
            if (dnsServerAddressStreamProvider != null) {
                builder.nameServerProvider(dnsServerAddressStreamProvider);
            }
            assert meterRegistry != null;
            final DnsQueryLifecycleObserverFactory observerFactory =
                    new DefaultDnsQueryLifecycleObserverFactory(
                        meterRegistry,
                        new MeterIdPrefix("armeria.client.dns.queries"));
            if (dnsQueryLifecycleObserverFactory == null) {
                builder.dnsQueryLifecycleObserverFactory(observerFactory);
            } else {
                builder.dnsQueryLifecycleObserverFactory(
                        new BiDnsQueryLifecycleObserverFactory(
                                observerFactory,
                                dnsQueryLifecycleObserverFactory));
            }
            if (searchDomains != null) {
                builder.searchDomains(searchDomains);
            }
            if (ndots != null) {
                builder.ndots(ndots);
            }
            if (decodeIdn != null) {
                builder.decodeIdn(decodeIdn);
            }
        };
        final String cacheSpec = firstNonNull(this.cacheSpec, Flags.dnsCacheSpec());
        return new RefreshingAddressResolverGroup(resolverConfigurator, minTtl, maxTtl, negativeTtl,
                                                  queryTimeoutMillis, refreshBackoff, resolvedAddressTypes,
                                                  cacheSpec);
    }
}
