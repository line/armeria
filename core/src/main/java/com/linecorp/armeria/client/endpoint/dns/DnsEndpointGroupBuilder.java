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
import static com.linecorp.armeria.internal.client.dns.DnsUtil.defaultDnsQueryTimeoutMillis;
import static java.util.Objects.requireNonNull;

import java.net.IDN;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.AbstractDnsResolverBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.AbstractDynamicEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroupSetters;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.internal.client.dns.DefaultDnsResolver;
import com.linecorp.armeria.internal.client.dns.DnsUtil;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.dns.DnsNameResolverBuilder;

abstract class DnsEndpointGroupBuilder<SELF extends DnsEndpointGroupBuilder<SELF>>
        extends AbstractDnsResolverBuilder<SELF> implements DynamicEndpointGroupSetters<SELF> {

    private final String hostname;
    @Nullable
    private EventLoop eventLoop;
    private Backoff backoff = Backoff.exponential(1000, 32000).withJitter(0.2);
    private EndpointSelectionStrategy selectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();
    private final DnsDynamicEndpointGroupBuilder dnsDynamicEndpointGroupBuilder;
    private final List<DnsQueryListener> dnsQueryListeners = new ArrayList<>();

    DnsEndpointGroupBuilder(String hostname) {
        this.hostname = Ascii.toLowerCase(IDN.toASCII(requireNonNull(hostname, "hostname"),
                                                      IDN.ALLOW_UNASSIGNED));
        // Use the default queryTimeoutMillis(5000) as the default selection timeout.
        dnsDynamicEndpointGroupBuilder = new DnsDynamicEndpointGroupBuilder(defaultDnsQueryTimeoutMillis());
    }

    final String hostname() {
        return hostname;
    }

    /**
     * Returns the {@link EventLoop} set via {@link #eventLoop(EventLoop)} or acquires a random
     * {@link EventLoop} from {@link CommonPools#workerGroup()}.
     */
    final EventLoop getOrAcquireEventLoop() {
        if (eventLoop != null) {
            return eventLoop;
        } else {
            return CommonPools.workerGroup().next();
        }
    }

    /**
     * Sets the {@link EventLoop} to use for sending DNS queries.
     */
    public SELF eventLoop(EventLoop eventLoop) {
        requireNonNull(eventLoop, "eventLoop");
        checkArgument(TransportType.isSupported(eventLoop), "unsupported event loop type: %s", eventLoop);

        this.eventLoop = eventLoop;
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
    public SELF backoff(Backoff backoff) {
        this.backoff = requireNonNull(backoff, "backoff");
        return self();
    }

    /**
     * Sets the {@link EndpointSelectionStrategy} that determines the enumeration order of {@link Endpoint}s.
     */
    public SELF selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        this.selectionStrategy = requireNonNull(selectionStrategy, "selectionStrategy");
        return self();
    }

    final EndpointSelectionStrategy selectionStrategy() {
        return selectionStrategy;
    }

    final boolean shouldAllowEmptyEndpoints() {
        return dnsDynamicEndpointGroupBuilder.shouldAllowEmptyEndpoints();
    }

    @Override
    public SELF allowEmptyEndpoints(boolean allowEmptyEndpoints) {
        dnsDynamicEndpointGroupBuilder.allowEmptyEndpoints(allowEmptyEndpoints);
        return self();
    }

    @Override
    public SELF selectionTimeout(Duration selectionTimeout) {
        dnsDynamicEndpointGroupBuilder.selectionTimeout(selectionTimeout);
        return self();
    }

    /**
     * Sets the timeout to wait until a successful {@link Endpoint} selection.
     * {@code 0} disables the timeout.
     * If unspecified, the default DNS query timeout ({@link DnsUtil#DEFAULT_DNS_QUERY_TIMEOUT_MILLIS} ms) is
     * used by default.
     */
    @Override
    public SELF selectionTimeoutMillis(long selectionTimeoutMillis) {
        dnsDynamicEndpointGroupBuilder.selectionTimeoutMillis(selectionTimeoutMillis);
        return self();
    }

    final long selectionTimeoutMillis() {
        return dnsDynamicEndpointGroupBuilder.selectionTimeoutMillis();
    }

    final DefaultDnsResolver buildResolver(EventLoop eventLoop) {
        return buildResolver(unused -> {}, eventLoop);
    }

    final DefaultDnsResolver buildResolver(Consumer<DnsNameResolverBuilder> customizer, EventLoop eventLoop) {
        final DnsNameResolverBuilder resolverBuilder = new DnsNameResolverBuilder(eventLoop);
        customizer.accept(resolverBuilder);
        buildConfigurator(eventLoop.parent()).accept(resolverBuilder);

        return DefaultDnsResolver.of(resolverBuilder.build(), maybeCreateDnsCache(), eventLoop,
                                     searchDomains(), ndots(), queryTimeoutMillis(),
                                     hostsFileEntriesResolver());
    }

    /**
     * Adds the {@link DnsQueryListener}s which listens to the result of {@link DnsRecord} queries.
     * If no {@link DnsQueryListener} is configured, {@link DnsQueryListener#of()} is used by default.
     */
    @UnstableApi
    public SELF addDnsQueryListeners(
            Iterable<? extends DnsQueryListener> dnsQueryListeners) {
        requireNonNull(dnsQueryListeners, "dnsQueryListeners");
        for (DnsQueryListener listener: dnsQueryListeners) {
            this.dnsQueryListeners.add(listener);
        }
        return self();
    }

    /**
     * Adds the {@link DnsQueryListener} that listens to the result of {@link DnsRecord} queries.
     * If no {@link DnsQueryListener} is configured, {@link DnsQueryListener#of()} is used by default.
     */
    @UnstableApi
    public SELF addDnsQueryListeners(DnsQueryListener... dnsQueryListeners) {
        requireNonNull(dnsQueryListeners, "dnsQueryListeners");
        return addDnsQueryListeners(ImmutableList.copyOf(dnsQueryListeners));
    }

    final List<DnsQueryListener> dnsQueryListeners() {
        return dnsQueryListeners;
    }

    /**
     * This workaround delegates DynamicEndpointGroupSetters properties to AbstractDynamicEndpointGroupBuilder.
     * DnsEndpointGroupBuilder can't extend AbstractDynamicEndpointGroupBuilder because it already extends
     * AbstractDnsResolverBuilder.
     */
    private static class DnsDynamicEndpointGroupBuilder
            extends AbstractDynamicEndpointGroupBuilder<DnsDynamicEndpointGroupBuilder> {
        protected DnsDynamicEndpointGroupBuilder(long selectionTimeoutMillis) {
            super(selectionTimeoutMillis);
        }

        @Override
        public boolean shouldAllowEmptyEndpoints() {
            return super.shouldAllowEmptyEndpoints();
        }

        @Override
        public long selectionTimeoutMillis() {
            return super.selectionTimeoutMillis();
        }
    }
}
