/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.dns;

import static com.google.common.base.Preconditions.checkArgument;

import java.net.InetSocketAddress;
import java.time.Duration;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.DnsCache;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;

/**
 * Builds a new {@link DnsAddressEndpointGroup} that sources its {@link Endpoint} list from the {@code A} or
 * {@code AAAA} DNS records of a certain hostname.
 */
public final class DnsAddressEndpointGroupBuilder extends DnsEndpointGroupBuilder {

    private int port;
    @Nullable
    private ResolvedAddressTypes resolvedAddressTypes;

    DnsAddressEndpointGroupBuilder(String hostname) {
        super(hostname);
    }

    /**
     * Sets the port number of the {@link Endpoint}s created by {@link DnsAddressEndpointGroup}.
     * By default, the port number of the {@link Endpoint}s will remain unspecified and the protocol-dependent
     * default port number will be chosen automatically, e.g. 80 or 443.
     */
    public DnsAddressEndpointGroupBuilder port(int port) {
        checkArgument(port > 0 && port <= 65535, "port: %s (expected: 1...65535)", port);
        this.port = port;
        return this;
    }

    @VisibleForTesting
    DnsAddressEndpointGroupBuilder resolvedAddressTypes(ResolvedAddressTypes resolvedAddressTypes) {
        this.resolvedAddressTypes = resolvedAddressTypes;
        return this;
    }

    /**
     * Returns a newly created {@link DnsAddressEndpointGroup}.
     */
    public DnsAddressEndpointGroup build() {
        return new DnsAddressEndpointGroup(selectionStrategy(), eventLoop(), backoff(), minTtl(), maxTtl(),
                                           resolvedAddressTypes, hostname(), port, dnsResolverFactory());
    }

    // Override the return type of the chaining methods in the DnsEndpointGroupBuilder.

    @Override
    public DnsAddressEndpointGroupBuilder eventLoop(EventLoop eventLoop) {
        return (DnsAddressEndpointGroupBuilder) super.eventLoop(eventLoop);
    }

    @Override
    public DnsAddressEndpointGroupBuilder backoff(Backoff backoff) {
        return (DnsAddressEndpointGroupBuilder) super.backoff(backoff);
    }

    @Override
    public DnsAddressEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        return (DnsAddressEndpointGroupBuilder) super.selectionStrategy(selectionStrategy);
    }

    // Override the return type of the chaining methods in the AbstractDnsResolverBuilder.

    @Override
    public DnsAddressEndpointGroupBuilder traceEnabled(boolean traceEnabled) {
        return (DnsAddressEndpointGroupBuilder) super.traceEnabled(traceEnabled);
    }

    @Override
    public DnsAddressEndpointGroupBuilder queryTimeout(Duration queryTimeout) {
        return (DnsAddressEndpointGroupBuilder) super.queryTimeout(queryTimeout);
    }

    @Override
    public DnsAddressEndpointGroupBuilder queryTimeoutMillis(long queryTimeoutMillis) {
        return (DnsAddressEndpointGroupBuilder) super.queryTimeoutMillis(queryTimeoutMillis);
    }

    @Override
    public DnsAddressEndpointGroupBuilder queryTimeoutForEachAttempt(Duration queryTimeoutForEachAttempt) {
        return (DnsAddressEndpointGroupBuilder) super.queryTimeoutForEachAttempt(queryTimeoutForEachAttempt);
    }

    @Override
    public DnsAddressEndpointGroupBuilder queryTimeoutMillisForEachAttempt(
            long queryTimeoutMillisForEachAttempt) {
        return (DnsAddressEndpointGroupBuilder) super.queryTimeoutMillisForEachAttempt(
                queryTimeoutMillisForEachAttempt);
    }

    @Override
    public DnsAddressEndpointGroupBuilder recursionDesired(boolean recursionDesired) {
        return (DnsAddressEndpointGroupBuilder) super.recursionDesired(recursionDesired);
    }

    @Override
    public DnsAddressEndpointGroupBuilder maxQueriesPerResolve(int maxQueriesPerResolve) {
        return (DnsAddressEndpointGroupBuilder) super.maxQueriesPerResolve(maxQueriesPerResolve);
    }

    @Override
    public DnsAddressEndpointGroupBuilder serverAddresses(InetSocketAddress... serverAddresses) {
        return (DnsAddressEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsAddressEndpointGroupBuilder serverAddresses(Iterable<InetSocketAddress> serverAddresses) {
        return (DnsAddressEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsAddressEndpointGroupBuilder serverAddressStreamProvider(
            DnsServerAddressStreamProvider serverAddressStreamProvider) {
        return (DnsAddressEndpointGroupBuilder) super.serverAddressStreamProvider(serverAddressStreamProvider);
    }

    @Override
    public DnsAddressEndpointGroupBuilder dnsServerAddressStreamProvider(
            DnsServerAddressStreamProvider dnsServerAddressStreamProvider) {
        return (DnsAddressEndpointGroupBuilder) super.dnsServerAddressStreamProvider(
                dnsServerAddressStreamProvider);
    }

    @Override
    public DnsAddressEndpointGroupBuilder maxPayloadSize(int maxPayloadSize) {
        return (DnsAddressEndpointGroupBuilder) super.maxPayloadSize(maxPayloadSize);
    }

    @Override
    public DnsAddressEndpointGroupBuilder optResourceEnabled(boolean optResourceEnabled) {
        return (DnsAddressEndpointGroupBuilder) super.optResourceEnabled(optResourceEnabled);
    }

    @Override
    public DnsAddressEndpointGroupBuilder hostsFileEntriesResolver(
            HostsFileEntriesResolver hostsFileEntriesResolver) {
        return (DnsAddressEndpointGroupBuilder) super.hostsFileEntriesResolver(hostsFileEntriesResolver);
    }

    @Override
    public DnsAddressEndpointGroupBuilder dnsQueryLifecycleObserverFactory(
            DnsQueryLifecycleObserverFactory observerFactory) {
        return (DnsAddressEndpointGroupBuilder) super.dnsQueryLifecycleObserverFactory(observerFactory);
    }

    @Override
    public DnsAddressEndpointGroupBuilder disableDnsQueryMetrics() {
        return (DnsAddressEndpointGroupBuilder) super.disableDnsQueryMetrics();
    }

    @Override
    public DnsAddressEndpointGroupBuilder enableDnsQueryMetrics(boolean enable) {
        return (DnsAddressEndpointGroupBuilder) super.enableDnsQueryMetrics(enable);
    }

    @Override
    public DnsAddressEndpointGroupBuilder searchDomains(String... searchDomains) {
        return (DnsAddressEndpointGroupBuilder) super.searchDomains(searchDomains);
    }

    @Override
    public DnsAddressEndpointGroupBuilder searchDomains(Iterable<String> searchDomains) {
        return (DnsAddressEndpointGroupBuilder) super.searchDomains(searchDomains);
    }

    @Override
    public DnsAddressEndpointGroupBuilder ndots(int ndots) {
        return (DnsAddressEndpointGroupBuilder) super.ndots(ndots);
    }

    @Override
    public DnsAddressEndpointGroupBuilder decodeIdn(boolean decodeIdn) {
        return (DnsAddressEndpointGroupBuilder) super.decodeIdn(decodeIdn);
    }

    @Override
    public DnsAddressEndpointGroupBuilder meterRegistry(MeterRegistry meterRegistry) {
        return (DnsAddressEndpointGroupBuilder) super.meterRegistry(meterRegistry);
    }

    @Override
    public DnsAddressEndpointGroupBuilder cacheSpec(String cacheSpec) {
        return (DnsAddressEndpointGroupBuilder) super.cacheSpec(cacheSpec);
    }

    @Override
    public DnsAddressEndpointGroupBuilder ttl(int minTtl, int maxTtl) {
        return (DnsAddressEndpointGroupBuilder) super.ttl(minTtl, maxTtl);
    }

    @Override
    public DnsAddressEndpointGroupBuilder negativeTtl(int negativeTtl) {
        return (DnsAddressEndpointGroupBuilder) super.negativeTtl(negativeTtl);
    }

    @Override
    public DnsAddressEndpointGroupBuilder dnsCache(DnsCache dnsCache) {
        return (DnsAddressEndpointGroupBuilder) super.dnsCache(dnsCache);
    }
}
