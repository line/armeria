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

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.EventLoop;
import io.netty.resolver.ResolvedAddressTypes;

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
        return new DnsAddressEndpointGroup(selectionStrategy(), eventLoop(), minTtl(), maxTtl(), negativeTtl(),
                                           queryTimeoutMillis(), serverAddressStreamProvider(), backoff(),
                                           resolvedAddressTypes, hostname(), port);
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public DnsAddressEndpointGroupBuilder eventLoop(EventLoop eventLoop) {
        return (DnsAddressEndpointGroupBuilder) super.eventLoop(eventLoop);
    }

    @Override
    public DnsAddressEndpointGroupBuilder ttl(int minTtl, int maxTtl) {
        return (DnsAddressEndpointGroupBuilder) super.ttl(minTtl, maxTtl);
    }

    @Override
    public DnsAddressEndpointGroupBuilder setNegativeTtl(int ttl) {
        return (DnsAddressEndpointGroupBuilder) super.setNegativeTtl(ttl);
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
    public DnsAddressEndpointGroupBuilder serverAddresses(InetSocketAddress... serverAddresses) {
        return (DnsAddressEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsAddressEndpointGroupBuilder serverAddresses(Iterable<InetSocketAddress> serverAddresses) {
        return (DnsAddressEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsAddressEndpointGroupBuilder backoff(Backoff backoff) {
        return (DnsAddressEndpointGroupBuilder) super.backoff(backoff);
    }

    @Override
    public DnsAddressEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        return (DnsAddressEndpointGroupBuilder) super.selectionStrategy(selectionStrategy);
    }
}
