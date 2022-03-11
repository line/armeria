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
import java.util.function.Consumer;

import com.google.common.base.Ascii;

import com.linecorp.armeria.client.AbstractDnsResolverBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.internal.client.dns.DefaultDnsResolver;

import io.netty.channel.EventLoop;
import io.netty.resolver.dns.DnsNameResolverBuilder;

abstract class DnsEndpointGroupBuilder extends AbstractDnsResolverBuilder {

    private final String hostname;
    @Nullable
    private EventLoop eventLoop;
    private Backoff backoff = Backoff.exponential(1000, 32000).withJitter(0.2);
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();

    DnsEndpointGroupBuilder(String hostname) {
        this.hostname =
                Ascii.toLowerCase(IDN.toASCII(requireNonNull(hostname, "hostname"), IDN.ALLOW_UNASSIGNED));
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
    public DnsEndpointGroupBuilder eventLoop(EventLoop eventLoop) {
        requireNonNull(eventLoop, "eventLoop");
        checkArgument(TransportType.isSupported(eventLoop), "unsupported event loop type: %s", eventLoop);

        this.eventLoop = eventLoop;
        return this;
    }

    final Backoff backoff() {
        return backoff;
    }

    /**
     * Sets the {@link Backoff} that determines how much delay should be inserted between queries when a DNS
     * server sent an error response. {@code Backoff.exponential(1000, 32000).withJitter(0.2)} is used by
     * default.
     */
    public DnsEndpointGroupBuilder backoff(Backoff backoff) {
        this.backoff = requireNonNull(backoff, "backoff");
        return this;
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} that determines the enumeration order of {@link Endpoint}s.
     */
    public DnsEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return this;
    }

    final EndpointSelectionStrategy selectionStrategy() {
        return selectionStrategy;
    }

    DefaultDnsResolver buildResolver() {
        return buildResolver(unused -> {});
    }

    DefaultDnsResolver buildResolver(Consumer<DnsNameResolverBuilder> customizer) {
        final EventLoop eventLoop = eventLoop();
        final DnsNameResolverBuilder resolverBuilder = new DnsNameResolverBuilder(eventLoop);
        customizer.accept(resolverBuilder);
        buildConfigurator(eventLoop.parent()).accept(resolverBuilder, eventLoop);

        return DefaultDnsResolver.of(resolverBuilder.build(), maybeCreateDnsCache(), eventLoop,
                                     searchDomains(), ndots(), queryTimeoutMillis(),
                                     hostsFileEntriesResolver());
    }
}
