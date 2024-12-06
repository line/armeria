/*
 * Copyright 2024 LINE Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.net.InetSocketAddress;
import java.util.function.Function;
import java.util.stream.Stream;

import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;

import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddresses;

public final class DNSResolverFacadeUtils {

    private DNSResolverFacadeUtils() { }

    public static Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>> getAddressResolverGroupForTest(
            TestDnsServer dnsServer) {
        return eventLoopGroup -> {
            final DnsResolverGroupBuilder builder = builder(dnsServer);
            builder.autoRefreshBackoff(Backoff.fixed(0L));
            return builder.build(eventLoopGroup);
        };
    }

    private static DnsResolverGroupBuilder builder(TestDnsServer... servers) {
        return builder(true, servers);
    }

    private static DnsResolverGroupBuilder builder(boolean withCacheOption, TestDnsServer... servers) {
        final DnsServerAddressStreamProvider dnsServerAddressStreamProvider =
                hostname -> DnsServerAddresses.sequential(
                        Stream.of(servers).map(TestDnsServer::addr).collect(toImmutableList())).stream();
        final DnsResolverGroupBuilder builder = new DnsResolverGroupBuilder()
                .serverAddressStreamProvider(dnsServerAddressStreamProvider)
                .meterRegistry(PrometheusMeterRegistries.newRegistry())
                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                .traceEnabled(false);
        if (withCacheOption) {
            builder.dnsCache(DnsCache.builder().build());
        }
        return builder;
    }
}
