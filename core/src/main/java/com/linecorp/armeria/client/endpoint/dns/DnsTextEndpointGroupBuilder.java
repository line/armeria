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

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Function;

import com.linecorp.armeria.client.DnsCache;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.dns.DnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;

/**
 * Builds a new {@link DnsTextEndpointGroup} that sources its {@link Endpoint} list from the {@code TXT}
 * DNS records of a certain hostname.
 */
public final class DnsTextEndpointGroupBuilder extends DnsEndpointGroupBuilder {

    private final Function<byte[], @Nullable Endpoint> mapping;

    DnsTextEndpointGroupBuilder(String hostname, Function<byte[], @Nullable Endpoint> mapping) {
        super(hostname);
        this.mapping = requireNonNull(mapping, "mapping");
    }

    /**
     * Returns a newly created {@link DnsTextEndpointGroup}.
     */
    public DnsTextEndpointGroup build() {
        return new DnsTextEndpointGroup(selectionStrategy(), eventLoop(), backoff(), minTtl(), maxTtl(),
                                        hostname(), mapping, buildResolver());
    }

    // Override the return type of the chaining methods in the DnsEndpointGroupBuilder.

    @Override
    public DnsTextEndpointGroupBuilder eventLoop(EventLoop eventLoop) {
        return (DnsTextEndpointGroupBuilder) super.eventLoop(eventLoop);
    }

    @Override
    public DnsTextEndpointGroupBuilder backoff(Backoff backoff) {
        return (DnsTextEndpointGroupBuilder) super.backoff(backoff);
    }

    @Override
    public DnsTextEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        return (DnsTextEndpointGroupBuilder) super.selectionStrategy(selectionStrategy);
    }

    // Override the return type of the chaining methods in the AbstractDnsResolverBuilder.

    @Override
    public DnsTextEndpointGroupBuilder traceEnabled(boolean traceEnabled) {
        return (DnsTextEndpointGroupBuilder) super.traceEnabled(traceEnabled);
    }

    @Override
    public DnsTextEndpointGroupBuilder queryTimeout(Duration queryTimeout) {
        return (DnsTextEndpointGroupBuilder) super.queryTimeout(queryTimeout);
    }

    @Override
    public DnsTextEndpointGroupBuilder queryTimeoutMillis(long queryTimeoutMillis) {
        return (DnsTextEndpointGroupBuilder) super.queryTimeoutMillis(queryTimeoutMillis);
    }

    @Override
    public DnsTextEndpointGroupBuilder queryTimeoutForEachAttempt(Duration queryTimeoutForEachAttempt) {
        return (DnsTextEndpointGroupBuilder) super.queryTimeoutForEachAttempt(queryTimeoutForEachAttempt);
    }

    @Override
    public DnsTextEndpointGroupBuilder queryTimeoutMillisForEachAttempt(
            long queryTimeoutMillisForEachAttempt) {
        return (DnsTextEndpointGroupBuilder) super.queryTimeoutMillisForEachAttempt(
                queryTimeoutMillisForEachAttempt);
    }

    @Override
    public DnsTextEndpointGroupBuilder recursionDesired(boolean recursionDesired) {
        return (DnsTextEndpointGroupBuilder) super.recursionDesired(recursionDesired);
    }

    @Override
    public DnsTextEndpointGroupBuilder maxQueriesPerResolve(int maxQueriesPerResolve) {
        return (DnsTextEndpointGroupBuilder) super.maxQueriesPerResolve(maxQueriesPerResolve);
    }

    @Override
    public DnsTextEndpointGroupBuilder serverAddresses(InetSocketAddress... serverAddresses) {
        return (DnsTextEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsTextEndpointGroupBuilder serverAddresses(Iterable<InetSocketAddress> serverAddresses) {
        return (DnsTextEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsTextEndpointGroupBuilder serverAddressStreamProvider(
            DnsServerAddressStreamProvider serverAddressStreamProvider) {
        return (DnsTextEndpointGroupBuilder) super.serverAddressStreamProvider(serverAddressStreamProvider);
    }

    @Deprecated
    @Override
    public DnsTextEndpointGroupBuilder dnsServerAddressStreamProvider(
            DnsServerAddressStreamProvider dnsServerAddressStreamProvider) {
        return (DnsTextEndpointGroupBuilder) super.dnsServerAddressStreamProvider(
                dnsServerAddressStreamProvider);
    }

    @Override
    public DnsTextEndpointGroupBuilder maxPayloadSize(int maxPayloadSize) {
        return (DnsTextEndpointGroupBuilder) super.maxPayloadSize(maxPayloadSize);
    }

    @Override
    public DnsTextEndpointGroupBuilder optResourceEnabled(boolean optResourceEnabled) {
        return (DnsTextEndpointGroupBuilder) super.optResourceEnabled(optResourceEnabled);
    }

    @Override
    public DnsTextEndpointGroupBuilder hostsFileEntriesResolver(
            HostsFileEntriesResolver hostsFileEntriesResolver) {
        return (DnsTextEndpointGroupBuilder) super.hostsFileEntriesResolver(hostsFileEntriesResolver);
    }

    @Override
    public DnsTextEndpointGroupBuilder dnsQueryLifecycleObserverFactory(
            DnsQueryLifecycleObserverFactory observerFactory) {
        return (DnsTextEndpointGroupBuilder) super.dnsQueryLifecycleObserverFactory(observerFactory);
    }

    @Deprecated
    @Override
    public DnsTextEndpointGroupBuilder disableDnsQueryMetrics() {
        return (DnsTextEndpointGroupBuilder) super.disableDnsQueryMetrics();
    }

    @Override
    public DnsTextEndpointGroupBuilder enableDnsQueryMetrics(boolean enable) {
        return (DnsTextEndpointGroupBuilder) super.enableDnsQueryMetrics(enable);
    }

    @Override
    public DnsTextEndpointGroupBuilder searchDomains(String... searchDomains) {
        return (DnsTextEndpointGroupBuilder) super.searchDomains(searchDomains);
    }

    @Override
    public DnsTextEndpointGroupBuilder searchDomains(Iterable<String> searchDomains) {
        return (DnsTextEndpointGroupBuilder) super.searchDomains(searchDomains);
    }

    @Override
    public DnsTextEndpointGroupBuilder ndots(int ndots) {
        return (DnsTextEndpointGroupBuilder) super.ndots(ndots);
    }

    @Override
    public DnsTextEndpointGroupBuilder decodeIdn(boolean decodeIdn) {
        return (DnsTextEndpointGroupBuilder) super.decodeIdn(decodeIdn);
    }

    @Override
    public DnsTextEndpointGroupBuilder meterRegistry(MeterRegistry meterRegistry) {
        return (DnsTextEndpointGroupBuilder) super.meterRegistry(meterRegistry);
    }

    @Override
    public DnsTextEndpointGroupBuilder cacheSpec(String cacheSpec) {
        return (DnsTextEndpointGroupBuilder) super.cacheSpec(cacheSpec);
    }

    @Override
    public DnsTextEndpointGroupBuilder ttl(int minTtl, int maxTtl) {
        return (DnsTextEndpointGroupBuilder) super.ttl(minTtl, maxTtl);
    }

    @Override
    public DnsTextEndpointGroupBuilder negativeTtl(int negativeTtl) {
        return (DnsTextEndpointGroupBuilder) super.negativeTtl(negativeTtl);
    }

    @Override
    public DnsTextEndpointGroupBuilder dnsCache(DnsCache dnsCache) {
        return (DnsTextEndpointGroupBuilder) super.dnsCache(dnsCache);
    }

    @Override
    public DnsTextEndpointGroupBuilder allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        return (DnsTextEndpointGroupBuilder) super.allowEmptyEndpoints(allowEmptyEndpoints);
    }
}
