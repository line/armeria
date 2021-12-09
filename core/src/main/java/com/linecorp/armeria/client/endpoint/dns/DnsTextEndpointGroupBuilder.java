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

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.EventLoop;

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
        return new DnsTextEndpointGroup(selectionStrategy(), eventLoop(), minTtl(), maxTtl(), negativeTtl(),
                                        queryTimeoutMillis(), serverAddressStreamProvider(), backoff(),
                                        hostname(), mapping);
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public DnsTextEndpointGroupBuilder eventLoop(EventLoop eventLoop) {
        return (DnsTextEndpointGroupBuilder) super.eventLoop(eventLoop);
    }

    @Override
    public DnsTextEndpointGroupBuilder ttl(int minTtl, int maxTtl) {
        return (DnsTextEndpointGroupBuilder) super.ttl(minTtl, maxTtl);
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
    public DnsTextEndpointGroupBuilder serverAddresses(InetSocketAddress... serverAddresses) {
        return (DnsTextEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsTextEndpointGroupBuilder serverAddresses(Iterable<InetSocketAddress> serverAddresses) {
        return (DnsTextEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsTextEndpointGroupBuilder backoff(Backoff backoff) {
        return (DnsTextEndpointGroupBuilder) super.backoff(backoff);
    }

    @Override
    public DnsTextEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        return (DnsTextEndpointGroupBuilder) super.selectionStrategy(selectionStrategy);
    }

    @Override
    public DnsTextEndpointGroupBuilder negativeTtl(int ttl) {
        return (DnsTextEndpointGroupBuilder) super.negativeTtl(ttl);
    }
}
