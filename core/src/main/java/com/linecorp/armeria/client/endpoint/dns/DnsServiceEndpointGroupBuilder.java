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

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;

import io.netty.channel.EventLoop;

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
        return new DnsServiceEndpointGroup(selectionStrategy(), eventLoop(), minTtl(), maxTtl(), negativeTtl(),
                                           queryTimeoutMillis(), serverAddressStreamProvider(), backoff(),
                                           hostname());
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public DnsServiceEndpointGroupBuilder eventLoop(EventLoop eventLoop) {
        return (DnsServiceEndpointGroupBuilder) super.eventLoop(eventLoop);
    }

    @Override
    public DnsServiceEndpointGroupBuilder ttl(int minTtl, int maxTtl) {
        return (DnsServiceEndpointGroupBuilder) super.ttl(minTtl, maxTtl);
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
    public DnsServiceEndpointGroupBuilder serverAddresses(InetSocketAddress... serverAddresses) {
        return (DnsServiceEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsServiceEndpointGroupBuilder serverAddresses(Iterable<InetSocketAddress> serverAddresses) {
        return (DnsServiceEndpointGroupBuilder) super.serverAddresses(serverAddresses);
    }

    @Override
    public DnsServiceEndpointGroupBuilder backoff(Backoff backoff) {
        return (DnsServiceEndpointGroupBuilder) super.backoff(backoff);
    }

    @Override
    public DnsServiceEndpointGroupBuilder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
        return (DnsServiceEndpointGroupBuilder) super.selectionStrategy(selectionStrategy);
    }

    @Override
    public DnsServiceEndpointGroupBuilder negativeTtl(int negativeTtl) {
        return (DnsServiceEndpointGroupBuilder) super.negativeTtl(negativeTtl);
    }
}
