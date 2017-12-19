/*
 * Copyright 2017 LINE Corporation
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

import com.linecorp.armeria.internal.TransportType;

import io.netty.channel.EventLoop;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;

/** Utilities for DNS endpoint resolvers. */
final class DnsEndpointGroupUtil {

    /**
     * Creates a {@link DnsNameResolver} which queries on the provided {@link EventLoop}.
     */
    static DnsNameResolver createResolverForEventLoop(EventLoop eventLoop) {
        return new DnsNameResolverBuilder(requireNonNull(eventLoop, "eventLoop"))
                .channelFactory(new ReflectiveChannelFactory<>(
                        TransportType.datagramChannelType(eventLoop.parent())))
                .nameServerProvider(DnsServerAddressStreamProviders.platformDefault())
                .build();
    }

    private DnsEndpointGroupUtil() {}
}
