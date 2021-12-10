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

import static com.linecorp.armeria.internal.client.DnsUtil.extractAddressBytes;

import java.util.List;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.client.DnsQuestionWithoutTrailingDot;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.util.NetUtil;

/**
 * {@link DynamicEndpointGroup} which resolves targets using DNS address queries ({@code A} and {@code AAAA}).
 * This is useful for environments where service discovery is handled using DNS, e.g.
 * <a href="https://github.com/kubernetes/dns/blob/master/docs/specification.md">Kubernetes DNS-based service
 * discovery</a>.
 */
public final class DnsAddressEndpointGroup extends DnsEndpointGroup {

    /**
     * Creates a {@link DnsAddressEndpointGroup} with an unspecified port that schedules queries on a random
     * {@link EventLoop} from {@link CommonPools#workerGroup()}.
     *
     * @param hostname the hostname to query DNS queries for
     */
    public static DnsAddressEndpointGroup of(String hostname) {
        return builder(hostname).build();
    }

    /**
     * Creates a {@link DnsAddressEndpointGroup} that schedules queries on a random {@link EventLoop} from
     * {@link CommonPools#workerGroup()}.
     *
     * @param hostname the hostname to query DNS queries for
     * @param port     the port of the {@link Endpoint}s
     */
    public static DnsAddressEndpointGroup of(String hostname, int port) {
        return builder(hostname).port(port).build();
    }

    /**
     * Returns a new {@link DnsAddressEndpointGroupBuilder} with the specified hostname.
     *
     * @param hostname the hostname to query DNS queries for
     */
    public static DnsAddressEndpointGroupBuilder builder(String hostname) {
        return new DnsAddressEndpointGroupBuilder(hostname);
    }

    private final String hostname;
    private final int port;

    DnsAddressEndpointGroup(EndpointSelectionStrategy selectionStrategy, EventLoop eventLoop,
                            int minTtl, int maxTtl, int negativeTtl, long queryTimeoutMillis,
                            DnsServerAddressStreamProvider serverAddressStreamProvider,
                            Backoff backoff, @Nullable ResolvedAddressTypes resolvedAddressTypes,
                            String hostname, int port) {

        super(selectionStrategy, eventLoop, minTtl, maxTtl, negativeTtl, queryTimeoutMillis,
              serverAddressStreamProvider, backoff, newQuestions(hostname, resolvedAddressTypes),
              resolverBuilder -> {
                  if (resolvedAddressTypes != null) {
                      resolverBuilder.resolvedAddressTypes(resolvedAddressTypes);
                  }
              });

        this.hostname = hostname;
        this.port = port;
        start();
    }

    private static List<DnsQuestion> newQuestions(
            String hostname, @Nullable ResolvedAddressTypes resolvedAddressTypes) {

        if (resolvedAddressTypes == null) {
            if (SystemInfo.hasIpV6()) {
                resolvedAddressTypes = ResolvedAddressTypes.IPV4_PREFERRED;
            } else {
                resolvedAddressTypes = ResolvedAddressTypes.IPV4_ONLY;
            }
        }

        final ImmutableList.Builder<DnsQuestion> builder = ImmutableList.builder();
        switch (resolvedAddressTypes) {
            case IPV4_ONLY:
            case IPV4_PREFERRED:
            case IPV6_PREFERRED:
                builder.add(DnsQuestionWithoutTrailingDot.of(hostname, DnsRecordType.A));
                break;
        }
        switch (resolvedAddressTypes) {
            case IPV6_ONLY:
            case IPV4_PREFERRED:
            case IPV6_PREFERRED:
                builder.add(DnsQuestionWithoutTrailingDot.of(hostname, DnsRecordType.AAAA));
                break;
        }
        return builder.build();
    }

    @Override
    ImmutableSortedSet<Endpoint> onDnsRecords(List<DnsRecord> records, int ttl) throws Exception {
        final ImmutableSortedSet.Builder<Endpoint> builder = ImmutableSortedSet.naturalOrder();
        final boolean hasLoopbackARecords =
                records.stream()
                       .filter(r -> r instanceof DnsRawRecord)
                       .map(DnsRawRecord.class::cast)
                       .anyMatch(r -> r.type() == DnsRecordType.A &&
                                     r.content().getByte(r.content().readerIndex()) == 127);

        for (DnsRecord r : records) {
            if (!(r instanceof DnsRawRecord)) {
                continue;
            }

            final byte[] addrBytes = extractAddressBytes(r, logger(), logPrefix());
            if (addrBytes == null) {
                continue;
            }

            final String ipAddr;
            final int contentLen = addrBytes.length;

            if (contentLen == 16) {
                // Convert some IPv6 addresses into IPv4 addresses to remove duplicate endpoints.
                if (addrBytes[0] == 0x00 && addrBytes[1] == 0x00 &&
                    addrBytes[2] == 0x00 && addrBytes[3] == 0x00 &&
                    addrBytes[4] == 0x00 && addrBytes[5] == 0x00 &&
                    addrBytes[6] == 0x00 && addrBytes[7] == 0x00 &&
                    addrBytes[8] == 0x00 && addrBytes[9] == 0x00) {

                    if (addrBytes[10] == 0x00 && addrBytes[11] == 0x00) {
                        if (addrBytes[12] == 0x00 && addrBytes[13] == 0x00 &&
                            addrBytes[14] == 0x00 && addrBytes[15] == 0x01) {
                            // Loopback address (::1)
                            if (hasLoopbackARecords) {
                                // Contains an IPv4 loopback address already; skip.
                                continue;
                            } else {
                                ipAddr = "::1";
                            }
                        } else {
                            // IPv4-compatible address.
                            ipAddr = NetUtil.bytesToIpAddress(addrBytes, 12, 4);
                        }
                    } else if (addrBytes[10] == -1 && addrBytes[11] == -1) {
                        // IPv4-mapped address.
                        ipAddr = NetUtil.bytesToIpAddress(addrBytes, 12, 4);
                    } else {
                        ipAddr = NetUtil.bytesToIpAddress(addrBytes);
                    }
                } else {
                    ipAddr = NetUtil.bytesToIpAddress(addrBytes);
                }
            } else {
                ipAddr = NetUtil.bytesToIpAddress(addrBytes);
            }

            final Endpoint endpoint = Endpoint.unsafeCreate(hostname, port);
            builder.add(endpoint.withIpAddr(ipAddr));
        }

        final ImmutableSortedSet<Endpoint> endpoints = builder.build();
        if (logger().isDebugEnabled()) {
            logger().debug("{} Resolved: {} (TTL: {})",
                           logPrefix(),
                           endpoints.stream()
                                    .map(Endpoint::ipAddr)
                                    .collect(Collectors.joining(", ")),
                           ttl);
        }

        return endpoints;
    }
}
