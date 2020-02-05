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
import static java.util.Objects.requireNonNull;

import java.net.IDN;
import java.net.InetSocketAddress;

import javax.annotation.Nullable;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.internal.common.util.TransportType;

import io.netty.channel.EventLoop;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.resolver.dns.DnsServerAddresses;

abstract class DnsEndpointGroupBuilder<B extends DnsEndpointGroupBuilder<B>> {

    private final String hostname;
    @Nullable
    private EventLoop eventLoop;
    private int minTtl = 1;
    private int maxTtl = Integer.MAX_VALUE;
    private DnsServerAddressStreamProvider serverAddressStreamProvider =
            DnsServerAddressStreamProviders.platformDefault();
    private Backoff backoff = Backoff.exponential(1000, 32000).withJitter(0.2);
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    DnsEndpointGroupBuilder(String hostname) {
        this.hostname = Ascii.toLowerCase(IDN.toASCII(requireNonNull(hostname, "hostname"),
                                                      IDN.ALLOW_UNASSIGNED));
    }

    /**
     * Returns {@code this}.
     */
    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;
    }

    final String hostname() {
        return hostname;
    }

    final EventLoop eventLoop() {
        if (eventLoop != null) {
            return eventLoop;
        } else {
            return CommonPools.workerGroup().next();
        }
    }

    /**
     * Sets the {@link EventLoop} to use for sending DNS queries.
     */
    public final B eventLoop(EventLoop eventLoop) {
        requireNonNull(eventLoop, "eventLoop");
        checkArgument(TransportType.isSupported(eventLoop),
                      "unsupported event loop type: %s", eventLoop);

        this.eventLoop = eventLoop;
        return self();
    }

    final int minTtl() {
        return minTtl;
    }

    final int maxTtl() {
        return maxTtl;
    }

    /**
     * Sets the minimum and maximum TTL of the DNS records (in seconds). If the TTL of the DNS record returned
     * by the DNS server is less than the minimum TTL or greater than the maximum TTL, the TTL from the DNS
     * server will be ignored and {@code minTtl} or {@code maxTtl} will be used respectively. The default
     * {@code minTtl} and {@code maxTtl} are {@code 1} and {@link Integer#MAX_VALUE}, which practically tells
     * to respect the server TTL.
     */
    public final B ttl(int minTtl, int maxTtl) {
        checkArgument(minTtl > 0 && minTtl <= maxTtl,
                      "minTtl: %s, maxTtl: %s (expected: 1 <= minTtl <= maxTtl)", minTtl, maxTtl);
        this.minTtl = minTtl;
        this.maxTtl = maxTtl;
        return self();
    }

    final DnsServerAddressStreamProvider serverAddressStreamProvider() {
        return serverAddressStreamProvider;
    }

    /**
     * Sets the DNS server addresses to send queries to. Operating system default is used by default.
     */
    public final B serverAddresses(InetSocketAddress... serverAddresses) {
        return serverAddresses(ImmutableList.copyOf(requireNonNull(serverAddresses, "serverAddresses")));
    }

    /**
     * Sets the DNS server addresses to send queries to. Operating system default is used by default.
     */
    public final B serverAddresses(Iterable<InetSocketAddress> serverAddresses) {
        requireNonNull(serverAddresses, "serverAddresses");
        final DnsServerAddresses addrs = DnsServerAddresses.sequential(serverAddresses);
        serverAddressStreamProvider = hostname -> addrs.stream();
        return self();
    }

    final Backoff backoff() {
        return backoff;
    }

    /**
     * Sets the {@link Backoff} that determines how much delay should be inserted between queries when a DNS
     * server sent an error response. {@code Backoff.exponential(1000, 32000).withJitter(0.2)} is used by
     * default.
     */
    public final B backoff(Backoff backoff) {
        this.backoff = requireNonNull(backoff, "backoff");
        return self();
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} that deteremines the enumeration order of {@link Endpoint}s.
     */
    public final B selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return self();
    }

    final EndpointSelectionStrategy selectionStrategy() {
        return selectionStrategy;
    }
}
