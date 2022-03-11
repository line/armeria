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

import java.net.InetSocketAddress;
import java.time.Duration;

import com.linecorp.armeria.client.DnsCache;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.dns.DnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;

/**
 * Builds a new {@link DnsServiceEndpointGroup} that sources its {@link Endpoint} list from the {@code SRV}
 * DNS records of a certain hostname.
 */
public final class DnsServiceEndpointGroupBuilder extends DnsEndpointGroupBuilder {

    DnsServiceEndpointGroupBuilder(String hostname) {
        super(hostname);
    }

    /**
     * Returns a newly created {@link DnsServiceEndpointGroup}.
     */
    public DnsServiceEndpointGroup build() {
        return new DnsServiceEndpointGroup(selectionStrategy(), eventLoop(), backoff(), minTtl(), maxTtl(),
                                           hostname(), buildResolver());
    }

    // Override the return type of the chaining methods in the DnsEndpointGroupBuilder.

    @Override
    public DnsServiceEndpointGroupBuilder eventLoop(EventLoop eventLoop) {
        return (DnsServiceEndpointGroupBuilder) super.eventLoop(eventLoop);
    }

    @Override
    public DnsServiceEndpointGroupBuilder backoff(Backoff backoff) {
        return (DnsServiceEndpointGroupBuilder) super.backoff(backoff);
    }

    @Override
    public DnsServiceEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        return (DnsServiceEndpointGroupBuilder) super.selectionStrategy(selectionStrategy);
    }

    // Override the return type of the chaining methods in the AbstractDnsResolverBuilder.

    @Override
    public DnsServiceEndpointGroupBuilder traceEnabled(boolean traceEnabled) {
        return (DnsServiceEndpointGroupBuilder) super.traceEnabled(traceEnabled);
    }

    @Override
    public DnsServiceEndpointGroupBuilder queryTimeout(Duration queryTimeout) {
        return (DnsServiceEndpointGroupBuilder) super.queryTimeout(queryTimeout);
    }

    @Override
    public DnsServiceEndpointGroupBuilder queryTimeoutMillis(long queryTimeoutMillis) {
        return (DnsServiceEndpointGroupBuilder) super.queryTimeoutMillis(queryTimeoutMillis);
    }

    @Override
    public DnsServiceEndpointGroupBuilder queryTimeoutForEachAttempt(Duration queryTimeoutForEachAttempt) {
        return (DnsServiceEndpointGroupBuilder) super.queryTimeoutForEachAttempt(queryTimeoutForEachAttempt);
    }

    @Override
    public DnsServiceEndpointGroupBuilder queryTimeoutMillisForEachAttempt(
            long queryTimeoutMillisForEachAttempt) {
        return (DnsServiceEndpointGroupBuilder) super.queryTimeoutMillisForEachAttempt(
                queryTimeoutMillisForEachAttempt);
    }

    @Override
    public DnsServiceEndpointGroupBuilder recursionDesired(boolean recursionDesired) {
        return (DnsServiceEndpointGroupBuilder) super.recursionDesired(recursionDesired);
    }

    @Override
    public DnsServiceEndpointGroupBuilder maxQueriesPerResolve(int maxQueriesPerResolve) {
        return (DnsServiceEndpointGroupBuilder) super.maxQueriesPerResolve(maxQueriesPerResolve);
    }

    @Override
    public DnsServiceEndpointGroupBuilder serverAddresses(InetSocketAddress... serverAddresses) {
        return (DnsServiceEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsServiceEndpointGroupBuilder serverAddresses(Iterable<InetSocketAddress> serverAddresses) {
        return (DnsServiceEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsServiceEndpointGroupBuilder serverAddressStreamProvider(
            DnsServerAddressStreamProvider serverAddressStreamProvider) {
        return (DnsServiceEndpointGroupBuilder) super.serverAddressStreamProvider(serverAddressStreamProvider);
    }

    @Deprecated
    @Override
    public DnsServiceEndpointGroupBuilder dnsServerAddressStreamProvider(
            DnsServerAddressStreamProvider dnsServerAddressStreamProvider) {
        return (DnsServiceEndpointGroupBuilder) super.dnsServerAddressStreamProvider(
                dnsServerAddressStreamProvider);
    }

    @Override
    public DnsServiceEndpointGroupBuilder maxPayloadSize(int maxPayloadSize) {
        return (DnsServiceEndpointGroupBuilder) super.maxPayloadSize(maxPayloadSize);
    }

    @Override
    public DnsServiceEndpointGroupBuilder optResourceEnabled(boolean optResourceEnabled) {
        return (DnsServiceEndpointGroupBuilder) super.optResourceEnabled(optResourceEnabled);
    }

    @Override
    public DnsServiceEndpointGroupBuilder hostsFileEntriesResolver(
            HostsFileEntriesResolver hostsFileEntriesResolver) {
        return (DnsServiceEndpointGroupBuilder) super.hostsFileEntriesResolver(hostsFileEntriesResolver);
    }

    @Override
    public DnsServiceEndpointGroupBuilder dnsQueryLifecycleObserverFactory(
            DnsQueryLifecycleObserverFactory observerFactory) {
        return (DnsServiceEndpointGroupBuilder) super.dnsQueryLifecycleObserverFactory(observerFactory);
    }

    @Deprecated
    @Override
    public DnsServiceEndpointGroupBuilder disableDnsQueryMetrics() {
        return (DnsServiceEndpointGroupBuilder) super.disableDnsQueryMetrics();
    }

    @Override
    public DnsServiceEndpointGroupBuilder enableDnsQueryMetrics(boolean enable) {
        return (DnsServiceEndpointGroupBuilder) super.enableDnsQueryMetrics(enable);
    }

    @Override
    public DnsServiceEndpointGroupBuilder searchDomains(String... searchDomains) {
        return (DnsServiceEndpointGroupBuilder) super.searchDomains(searchDomains);
    }

    @Override
    public DnsServiceEndpointGroupBuilder searchDomains(Iterable<String> searchDomains) {
        return (DnsServiceEndpointGroupBuilder) super.searchDomains(searchDomains);
    }

    @Override
    public DnsServiceEndpointGroupBuilder ndots(int ndots) {
        return (DnsServiceEndpointGroupBuilder) super.ndots(ndots);
    }

    @Override
    public DnsServiceEndpointGroupBuilder decodeIdn(boolean decodeIdn) {
        return (DnsServiceEndpointGroupBuilder) super.decodeIdn(decodeIdn);
    }

    @Override
    public DnsServiceEndpointGroupBuilder meterRegistry(MeterRegistry meterRegistry) {
        return (DnsServiceEndpointGroupBuilder) super.meterRegistry(meterRegistry);
    }

    @Override
    public DnsServiceEndpointGroupBuilder cacheSpec(String cacheSpec) {
        return (DnsServiceEndpointGroupBuilder) super.cacheSpec(cacheSpec);
    }

    @Override
    public DnsServiceEndpointGroupBuilder ttl(int minTtl, int maxTtl) {
        return (DnsServiceEndpointGroupBuilder) super.ttl(minTtl, maxTtl);
    }

    @Override
    public DnsServiceEndpointGroupBuilder negativeTtl(int negativeTtl) {
        return (DnsServiceEndpointGroupBuilder) super.negativeTtl(negativeTtl);
    }

    @Override
    public DnsServiceEndpointGroupBuilder dnsCache(DnsCache dnsCache) {
        return (DnsServiceEndpointGroupBuilder) super.dnsCache(dnsCache);
    }
}
