/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.internal.TransportType;

import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;

/**
 * The default {@link AddressResolverGroup} factory implementation, which enables asynchronous
 * DNS resolution and tracing by default.
 */
final class DefaultAddressResolverGroupFactory
        implements Function<EventLoopGroup, AddressResolverGroup<InetSocketAddress>> {

    private final Iterable<Consumer<? super DnsNameResolverBuilder>> customizers;

    DefaultAddressResolverGroupFactory(Iterable<Consumer<? super DnsNameResolverBuilder>> customizers) {
        this.customizers = requireNonNull(customizers, "customizers");
    }

    @Override
    public AddressResolverGroup<InetSocketAddress> apply(EventLoopGroup eventLoopGroup) {
        final DnsNameResolverBuilder nameResolverBuilder = new DnsNameResolverBuilder();
        nameResolverBuilder.nameServerProvider(DnsServerAddressStreamProviders.platformDefault());
        nameResolverBuilder.traceEnabled(true);
        customizers.forEach(customizer -> customizer.accept(nameResolverBuilder));
        nameResolverBuilder.channelType(TransportType.datagramChannelType(eventLoopGroup));
        return new DnsAddressResolverGroup(nameResolverBuilder);
    }
}
