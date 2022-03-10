/*
 * Copyright 2022 LINE Corporation
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
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.client.dns;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.common.util.SystemInfo;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.DefaultHostsFileEntriesResolver;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.util.NetUtil;
import io.netty.util.internal.PlatformDependent;

class HostsFileDnsResolver extends AbstractUnwrappable<DnsResolver> implements DnsResolver {

    // Forked from https://github.com/netty/netty/blob/9cd94547d0211c04b610878e5e267c8de2342b97/resolver-dns/src/main/java/io/netty/resolver/dns/DnsNameResolver.java
    private static final String LOCALHOST = "localhost";
    @Nullable
    private static final String WINDOWS_HOST_NAME;
    private static final InetAddress LOCALHOST_ADDRESS;

    static {
        if (NetUtil.isIpV4StackPreferred() || !SystemInfo.hasIpV6()) {
            LOCALHOST_ADDRESS = NetUtil.LOCALHOST4;
        } else {
            if (NetUtil.isIpV6AddressesPreferred()) {
                LOCALHOST_ADDRESS = NetUtil.LOCALHOST6;
            } else {
                LOCALHOST_ADDRESS = NetUtil.LOCALHOST4;
            }
        }

        String hostName;
        try {
            hostName = PlatformDependent.isWindows() ? InetAddress.getLocalHost().getHostName() : null;
        } catch (Exception ignore) {
            hostName = null;
        }
        WINDOWS_HOST_NAME = hostName;
    }

    /**
     * Checks whether the given hostname is the localhost/host (computer) name on Windows OS.
     * Windows OS removed the localhost/host (computer) name information from the hosts file in the later
     * versions
     * and such hostname cannot be resolved from hosts file.
     * See https://github.com/netty/netty/issues/5386
     * See https://github.com/netty/netty/issues/11142
     */
    private static boolean isLocalWindowsHost(String hostname) {
        return PlatformDependent.isWindows() &&
               (LOCALHOST.equalsIgnoreCase(hostname) ||
                (hostname.equalsIgnoreCase(WINDOWS_HOST_NAME)));
    }

    private final HostsFileEntriesResolver hostsFileEntriesResolver;
    private final ResolvedAddressTypes resolvedAddressTypes;

    HostsFileDnsResolver(DnsResolver delegate, HostsFileEntriesResolver hostsFileEntriesResolver,
                         ResolvedAddressTypes resolvedAddressTypes) {
        super(delegate);
        this.hostsFileEntriesResolver = hostsFileEntriesResolver;
        this.resolvedAddressTypes = resolvedAddressTypes;
    }

    @Override
    public CompletableFuture<List<DnsRecord>> resolve(DnsQuestionContext ctx, DnsQuestion question) {
        // Respect /etc/hosts as well if the record type is A or AAAA.
        final DnsRecordType type = question.type();
        final String hostname = question.name();

        if (type == DnsRecordType.A || type == DnsRecordType.AAAA) {
            final List<InetAddress> hostsFileEntries = resolveHostsFileEntries(hostname);
            if (hostsFileEntries != null) {
                final ImmutableList.Builder<DnsRecord> builder = ImmutableList.builder();
                for (InetAddress hostsFileEntry : hostsFileEntries) {
                    byte[] content = null;
                    if (hostsFileEntry instanceof Inet4Address) {
                        if (type == DnsRecordType.A) {
                            content = hostsFileEntry.getAddress();
                        }
                    } else if (hostsFileEntry instanceof Inet6Address) {
                        if (type == DnsRecordType.AAAA) {
                            content = hostsFileEntry.getAddress();
                        }
                    }
                    if (content != null) {
                        // Our current implementation does not support reloading the hosts file,
                        // so use a fairly large TTL (1 day, i.e. 86400 seconds).
                        builder.add(new ByteArrayDnsRecord(hostname, type, 86400, content));
                    }
                }
                final ImmutableList<DnsRecord> records = builder.build();
                if (!records.isEmpty()) {
                    return CompletableFuture.completedFuture(records);
                }
            }
        }

        return unwrap().resolve(ctx, question);
    }

    @Nullable
    private List<InetAddress> resolveHostsFileEntries(String hostname) {
        final List<InetAddress> addresses;
        if (hostsFileEntriesResolver instanceof DefaultHostsFileEntriesResolver) {
            addresses = ((DefaultHostsFileEntriesResolver) hostsFileEntriesResolver)
                    .addresses(hostname, resolvedAddressTypes);
        } else {
            final InetAddress address = hostsFileEntriesResolver.address(hostname, resolvedAddressTypes);
            addresses = address != null ? Collections.singletonList(address) : null;
        }
        return addresses == null && isLocalWindowsHost(hostname) ?
               ImmutableList.of(LOCALHOST_ADDRESS) : addresses;
    }

    @Override
    public void close() {
        unwrap().close();
    }
}
