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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.function.BiFunction;

import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.internal.client.dns.DefaultDnsResolver;
import com.linecorp.armeria.internal.client.dns.DnsUtil;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.dns.BiDnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.DnsServerAddressStream;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.resolver.dns.DnsServerAddresses;
import io.netty.resolver.dns.NoopAuthoritativeDnsServerCache;
import io.netty.resolver.dns.NoopDnsCache;
import io.netty.resolver.dns.NoopDnsCnameCache;
import io.netty.util.concurrent.EventExecutor;

/**
 * A skeletal builder implementation for DNS resolvers.
 */
@UnstableApi
public abstract class AbstractDnsResolverBuilder {

    private DnsCache dnsCache = DnsCache.of();
    private String cacheSpec = Flags.dnsCacheSpec();
    private int minTtl = 1;
    private int maxTtl = Integer.MAX_VALUE;
    private int negativeTtl;
    private boolean needsToCreateDnsCache;

    private boolean traceEnabled = true;
    private long queryTimeoutMillis = 5000; // 5 seconds.
    private long queryTimeoutMillisForEachAttempt = -1;

    private boolean recursionDesired = true;
    private int maxQueriesPerResolve = -1;
    private int maxPayloadSize = 4096;
    private boolean optResourceEnabled = true;

    private HostsFileEntriesResolver hostsFileEntriesResolver = HostsFileEntriesResolver.DEFAULT;
    private DnsServerAddressStreamProvider serverAddressStreamProvider =
            DnsServerAddressStreamProviders.platformDefault();
    @Nullable
    private DnsQueryLifecycleObserverFactory dnsQueryLifecycleObserverFactory;
    private boolean dnsQueryMetricsEnabled = true;
    private List<String> searchDomains = DnsUtil.defaultSearchDomains();
    private int ndots = DnsUtil.defaultNdots();
    private boolean decodeIdn = true;

    @Nullable
    private MeterRegistry meterRegistry;

    protected AbstractDnsResolverBuilder() {}

    /**
     * Sets if this resolver should generate detailed trace information in exception messages so that
     * it is easier to understand the cause of resolution failure. This flag is enabled by default.
     */
    public AbstractDnsResolverBuilder traceEnabled(boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
        return this;
    }

    /**
     * Sets the timeout of the DNS query performed by this resolver. {@code 0} disables the timeout.
     */
    public AbstractDnsResolverBuilder queryTimeout(Duration queryTimeout) {
        requireNonNull(queryTimeout, "queryTimeout");
        checkArgument(!queryTimeout.isNegative(), "queryTimeout: %s (expected: >= 0)", queryTimeout);
        return queryTimeoutMillis(queryTimeout.toMillis());
    }

    /**
     * Sets the timeout of the DNS query performed by this resolver in milliseconds.
     * {@code 0} disables the timeout.
     */
    public AbstractDnsResolverBuilder queryTimeoutMillis(long queryTimeoutMillis) {
        checkArgument(queryTimeoutMillis >= 0, "queryTimeoutMillis: %s (expected: >= 0)", queryTimeoutMillis);
        this.queryTimeoutMillis = queryTimeoutMillis;
        return this;
    }

    /**
     * Sets the timeout of each DNS query performed by this endpoint group.
     * This option is useful if you want to set a timeout for each
     * <a href="https://en.wikipedia.org/wiki/Search_domain">search domain</a> resolution.
     * If unspecified, the value of {@link #queryTimeout(Duration)} is used.
     */
    public AbstractDnsResolverBuilder queryTimeoutForEachAttempt(Duration queryTimeoutForEachAttempt) {
        requireNonNull(queryTimeoutForEachAttempt, "queryTimeoutForEachAttempt");
        final long queryTimeoutMillisForEachAttempt = queryTimeoutForEachAttempt.toMillis();
        checkArgument(queryTimeoutMillisForEachAttempt > 0, "queryTimeoutForEachAttempt: %s (expected: > 0)",
                      queryTimeoutForEachAttempt);
        return queryTimeoutMillisForEachAttempt(queryTimeoutMillisForEachAttempt);
    }

    /**
     * Sets the timeout of each DNS query performed by this endpoint group in milliseconds.
     * This option is useful if you want to set a timeout for each
     * <a href="https://en.wikipedia.org/wiki/Search_domain">search domain</a> resolution.
     * If unspecified, the value of {@link #queryTimeoutMillis(long)} is used.
     */
    public AbstractDnsResolverBuilder queryTimeoutMillisForEachAttempt(long queryTimeoutMillisForEachAttempt) {
        checkArgument(queryTimeoutMillisForEachAttempt > 0,
                      "queryTimeoutMillisForEachAttempt: %s (expected: > 0)", queryTimeoutMillisForEachAttempt);
        this.queryTimeoutMillisForEachAttempt = queryTimeoutMillisForEachAttempt;
        return this;
    }

    /**
     * Sets if this resolver has to send a DNS query with the RD (recursion desired) flag set.
     * This flag is enabled by default.
     */
    public AbstractDnsResolverBuilder recursionDesired(boolean recursionDesired) {
        this.recursionDesired = recursionDesired;
        return this;
    }

    /**
     * Sets the base value of maximum allowed number of DNS queries to send when resolving a host name.
     * The actual maximum allowed number of queries will be multiplied by the
     * {@link DnsServerAddressStream#size()}. For example, if {@code maxQueriesPerResolve} is 5 and
     * {@link DnsServerAddressStream#size()} is 2, DNS queries can be executed up to 10 times.
     * The {@link DnsServerAddressStream} is provided by {@link DnsServerAddressStreamProvider}.
     *
     * @see #serverAddressStreamProvider(DnsServerAddressStreamProvider)
     */
    public AbstractDnsResolverBuilder maxQueriesPerResolve(int maxQueriesPerResolve) {
        checkArgument(maxQueriesPerResolve > 0, "maxQueriesPerResolve: %s (expected: > 0)",
                      maxQueriesPerResolve);
        this.maxQueriesPerResolve = maxQueriesPerResolve;
        return this;
    }

    /**
     * Sets the DNS server addresses to send queries to. Operating system default is used by default.
     */
    public AbstractDnsResolverBuilder serverAddresses(InetSocketAddress... serverAddresses) {
        return serverAddresses(ImmutableList.copyOf(requireNonNull(serverAddresses, "serverAddresses")));
    }

    /**
     * Sets the DNS server addresses to send queries to. Operating system default is used by default.
     */
    public AbstractDnsResolverBuilder serverAddresses(Iterable<InetSocketAddress> serverAddresses) {
        final DnsServerAddresses addrs = DnsServerAddresses.sequential(serverAddresses);
        return serverAddressStreamProvider(hostname -> addrs.stream());
    }

    /**
     * Returns the {@link DnsServerAddressStreamProvider}.
     */
    protected final DnsServerAddressStreamProvider serverAddressStreamProvider() {
        return serverAddressStreamProvider;
    }

    /**
     * Sets the {@link DnsServerAddressStreamProvider} which is used to determine which DNS server is used to
     * resolve each hostname.
     */
    public AbstractDnsResolverBuilder serverAddressStreamProvider(
            DnsServerAddressStreamProvider serverAddressStreamProvider) {
        requireNonNull(serverAddressStreamProvider, "serverAddressStreamProvider");
        this.serverAddressStreamProvider = serverAddressStreamProvider;
        return this;
    }

    /**
     * Sets the {@link DnsServerAddressStreamProvider} which is used to determine which DNS server is used to
     * resolve each hostname.
     *
     * @deprecated Use {@link #serverAddressStreamProvider(DnsServerAddressStreamProvider)} instead.
     */
    @Deprecated
    public AbstractDnsResolverBuilder dnsServerAddressStreamProvider(
            DnsServerAddressStreamProvider dnsServerAddressStreamProvider) {
        return serverAddressStreamProvider(dnsServerAddressStreamProvider);
    }

    /**
     * Sets the capacity of the datagram packet buffer in bytes.
     */
    public AbstractDnsResolverBuilder maxPayloadSize(int maxPayloadSize) {
        checkArgument(maxPayloadSize > 0, "maxPayloadSize: %s (expected: > 0)", maxPayloadSize);
        this.maxPayloadSize = maxPayloadSize;
        return this;
    }

    /**
     * Enables the automatic inclusion of an optional records that tries to give the remote DNS server a hint
     * about how much data the resolver can read per response. Some DNS Server may not support this and so
     * fail to answer queries.
     */
    public AbstractDnsResolverBuilder optResourceEnabled(boolean optResourceEnabled) {
        this.optResourceEnabled = optResourceEnabled;
        return this;
    }

    /**
     * Sets the {@link HostsFileEntriesResolver} which is used to first check if the hostname is locally
     * aliased.
     */
    public AbstractDnsResolverBuilder hostsFileEntriesResolver(
            HostsFileEntriesResolver hostsFileEntriesResolver) {
        this.hostsFileEntriesResolver = hostsFileEntriesResolver;
        return this;
    }

    /**
     * Sets the {@link DnsQueryLifecycleObserverFactory} that is used to generate objects which can observe
     * individual DNS queries.
     */
    public AbstractDnsResolverBuilder dnsQueryLifecycleObserverFactory(
            DnsQueryLifecycleObserverFactory observerFactory) {
        requireNonNull(observerFactory, "observerFactory");
        dnsQueryLifecycleObserverFactory = observerFactory;
        return this;
    }

    /**
     * Disables the default {@link DnsQueryLifecycleObserverFactory} that collects DNS query metrics through
     * {@link MeterRegistry}.
     *
     * @deprecated Use {@link #enableDnsQueryMetrics(boolean)} instead.
     */
    @Deprecated
    public AbstractDnsResolverBuilder disableDnsQueryMetrics() {
        return enableDnsQueryMetrics(false);
    }

    /**
     * Enables the default {@link DnsQueryLifecycleObserverFactory} that collects DNS query
     * metrics through {@link MeterRegistry}. This option is enabled by default.
     */
    public AbstractDnsResolverBuilder enableDnsQueryMetrics(boolean enable) {
        dnsQueryMetricsEnabled = enable;
        return this;
    }

    /**
     * Sets the search domains of the resolver.
     */
    public AbstractDnsResolverBuilder searchDomains(String... searchDomains) {
        requireNonNull(searchDomains, "searchDomains");
        return searchDomains(ImmutableList.copyOf(searchDomains));
    }

    /**
     * Sets the list of search domains of the resolver.
     */
    public AbstractDnsResolverBuilder searchDomains(Iterable<String> searchDomains) {
        requireNonNull(searchDomains, "searchDomains");
        this.searchDomains = ImmutableList.copyOf(searchDomains);
        return this;
    }

    /**
     * Sets the number of dots which must appear in a name before an initial absolute query is made.
     */
    public AbstractDnsResolverBuilder ndots(int ndots) {
        checkArgument(ndots >= 0, "ndots: %s (expected: >= 0)", ndots);
        this.ndots = ndots;
        return this;
    }

    /**
     * Sets if the domain and host names should be decoded to unicode when received.
     * See <a href="https://datatracker.ietf.org/doc/rfc3492/">rfc3492</a>. This flag is enabled by default.
     */
    public AbstractDnsResolverBuilder decodeIdn(boolean decodeIdn) {
        this.decodeIdn = decodeIdn;
        return this;
    }

    @Nullable
    protected final MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /**
     * Sets {@link MeterRegistry} to collect the DNS query metrics.
     */
    public AbstractDnsResolverBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return this;
    }

    /**
     * Returns the {@linkplain CaffeineSpec Caffeine specification string}.
     */
    protected final String cacheSpec() {
        return cacheSpec;
    }

    /**
     * Sets the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the domain
     * names and their resolved addresses. If not set, {@link Flags#dnsCacheSpec()} is used by default.
     *
     * <p>Note that {@link #cacheSpec(String)} and {@link #dnsCache(DnsCache)} are mutually exclusive.
     */
    public AbstractDnsResolverBuilder cacheSpec(String cacheSpec) {
        requireNonNull(cacheSpec, "cacheSpec");
        this.cacheSpec = cacheSpec;
        needsToCreateDnsCache = true;
        return this;
    }

    /**
     * Returns the minimum TTL of the cached DNS resource records in seconds.
     */
    protected final int minTtl() {
        return minTtl;
    }

    /**
     * Returns the maximum TTL of the cached DNS resource records in seconds.
     */
    protected final int maxTtl() {
        return maxTtl;
    }

    /**
     * Sets the minimum and maximum TTL of the cached DNS resource records in seconds. If the TTL of the DNS
     * resource record returned by the DNS server is less than the minimum TTL or greater than the maximum TTL,
     * this resolver will ignore the TTL from the DNS server and use the minimum TTL or the maximum TTL instead
     * respectively.
     * The default value is {@code 1} and {@link Integer#MAX_VALUE}, which practically tells this resolver to
     * respect the TTL from the DNS server.
     *
     * <p>Note that {@link #ttl(int, int)} and {@link #dnsCache(DnsCache)} are mutually exclusive.
     */
    public AbstractDnsResolverBuilder ttl(int minTtl, int maxTtl) {
        checkArgument(minTtl > 0 && minTtl <= maxTtl,
                      "minTtl: %s, maxTtl: %s (expected: 1 <= minTtl <= maxTtl)", minTtl, maxTtl);
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
        needsToCreateDnsCache = true;
        return this;
    }

    /**
     * Returns the negative TTL of the failed DNS queries in seconds.
     */
    protected final int negativeTtl() {
        return negativeTtl;
    }

    /**
     * Sets the TTL of the cache for the failed DNS queries in seconds. The default value is {@code 0} which
     * means that the DNS resolver does not cache when DNS queries are failed.
     *
     * <p>Note that {@link #negativeTtl(int)} and {@link #dnsCache(DnsCache)} are mutually exclusive.
     */
    public AbstractDnsResolverBuilder negativeTtl(int negativeTtl) {
        checkArgument(negativeTtl >= 0, "negativeTtl: %s (expected: >= 0)", negativeTtl);
        needsToCreateDnsCache = true;
        this.negativeTtl = negativeTtl;
        return this;
    }

    /**
     * Sets the {@link DnsCache} that caches the resolved {@link DnsRecord}s and the cause of a failure if
     * negative cache is activated. This option is useful if you want to share a {@link DnsCache} with
     * multiple DNS resolvers. If unspecified, the default {@link DnsCache} is used.
     *
     * <p>Note that if {@link #cacheSpec(String)}, {@link #ttl(int, int)}, or {@link #negativeTtl(int)} is set,
     * the DNS resolver will create its own {@link DnsCache} using the properties.
     * Therefore, {@link #cacheSpec(String)}, {@link #ttl(int, int)}, and {@link #negativeTtl(int)} are
     * mutually exclusive with {@link #dnsCache(DnsCache)}.
     */
    @UnstableApi
    public AbstractDnsResolverBuilder dnsCache(DnsCache dnsCache) {
        requireNonNull(dnsCache, "dnsCache");
        this.dnsCache = dnsCache;
        return this;
    }

    /**
     * Returns a factory method that creates a {@link DefaultDnsResolver} with the properties set.
     */
    @UnstableApi
    protected final BiFunction<DnsNameResolverBuilder, EventExecutor, DefaultDnsResolver> dnsResolverFactory(
            EventLoopGroup eventLoopGroup) {

        if (needsToCreateDnsCache && dnsCache != DnsCache.of()) {
            throw new IllegalStateException(
                    "Cannot set dnsCache() with cacheSpec(), ttl(), or negativeTtl().");
        }
        if (queryTimeoutMillisForEachAttempt > -1) {
            checkState(queryTimeoutMillis >= queryTimeoutMillisForEachAttempt,
                       "queryTimeoutMillis: %s, queryTimeoutMillisForEachAttempt: %s (expected: " +
                       "queryTimeoutMillis >= queryTimeoutMillisForEachAttempt)",
                       queryTimeoutMillis, queryTimeoutMillisForEachAttempt);
        }

        final DnsCache dnsCache;
        final MeterRegistry meterRegistry = firstNonNull(this.meterRegistry, Metrics.globalRegistry);
        if (needsToCreateDnsCache) {
            dnsCache = DnsCache.builder()
                               .cacheSpec(cacheSpec)
                               .ttl(minTtl, maxTtl)
                               .negativeTtl(negativeTtl)
                               .meterRegistry(meterRegistry)
                               .build();
        } else {
            dnsCache = this.dnsCache;
        }

        final boolean traceEnabled = this.traceEnabled;
        final long queryTimeoutMillis = this.queryTimeoutMillis;
        final long queryTimeoutMillisForEachAttempt = this.queryTimeoutMillisForEachAttempt;
        final boolean recursionDesired = this.recursionDesired;
        final int maxQueriesPerResolve = this.maxQueriesPerResolve;
        final int maxPayloadSize = this.maxPayloadSize;
        final boolean optResourceEnabled = this.optResourceEnabled;
        final HostsFileEntriesResolver hostsFileEntriesResolver = this.hostsFileEntriesResolver;
        final DnsServerAddressStreamProvider serverAddressStreamProvider = this.serverAddressStreamProvider;
        final DnsQueryLifecycleObserverFactory dnsQueryLifecycleObserverFactory =
                this.dnsQueryLifecycleObserverFactory;
        final boolean dnsQueryMetricsEnabled = this.dnsQueryMetricsEnabled;
        final List<String> searchDomains = this.searchDomains;
        final int ndots = this.ndots;
        final boolean decodeIdn = this.decodeIdn;

        return (builder, executor) -> {
            builder.channelType(TransportType.datagramChannelType(eventLoopGroup))
                   .socketChannelType(TransportType.socketChannelType(eventLoopGroup))
                   .resolveCache(NoopDnsCache.INSTANCE)
                   .authoritativeDnsServerCache(NoopAuthoritativeDnsServerCache.INSTANCE)
                   .cnameCache(NoopDnsCnameCache.INSTANCE)
                   .traceEnabled(traceEnabled)
                   .completeOncePreferredResolved(true)
                   .recursionDesired(recursionDesired)
                   .maxQueriesPerResolve(maxQueriesPerResolve)
                   .maxPayloadSize(maxPayloadSize)
                   .optResourceEnabled(optResourceEnabled)
                   // Disable DnsNameResolver from resolving host files and use HostsFileDnsResolver instead.
                   .hostsFileEntriesResolver(NoopHostFileEntriesResolver.INSTANCE)
                   .nameServerProvider(serverAddressStreamProvider)
                   // Disable DnsNameResolver from resolving search domains and use SearchDomainDnsResolver
                   // instead.
                   .searchDomains(ImmutableList.of())
                   .decodeIdn(decodeIdn);

            if (queryTimeoutMillisForEachAttempt > 0) {
                builder.queryTimeoutMillis(queryTimeoutMillisForEachAttempt);
            } else {
                if (queryTimeoutMillis == 0) {
                    builder.queryTimeoutMillis(Long.MAX_VALUE);
                } else {
                    builder.queryTimeoutMillis(queryTimeoutMillis);
                }
            }

            DnsQueryLifecycleObserverFactory observerFactory = dnsQueryLifecycleObserverFactory;
            if (dnsQueryMetricsEnabled) {
                final DefaultDnsQueryLifecycleObserverFactory defaultObserverFactory =
                        new DefaultDnsQueryLifecycleObserverFactory(
                                meterRegistry, new MeterIdPrefix("armeria.client.dns.queries"));
                if (observerFactory == null) {
                    observerFactory = defaultObserverFactory;
                } else {
                    observerFactory = new BiDnsQueryLifecycleObserverFactory(
                            observerFactory, defaultObserverFactory);
                }
            }
            if (observerFactory != null) {
                builder.dnsQueryLifecycleObserverFactory(observerFactory);
            }
            final DnsNameResolver dnsNameResolver = builder.build();

            return DefaultDnsResolver.of(dnsNameResolver, dnsCache, executor, searchDomains, ndots,
                                         hostsFileEntriesResolver, queryTimeoutMillis);
        };
    }
}
