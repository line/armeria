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
/*
 * Copyright (c) 1998-2011, Brian Wellington.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   * Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *
 *   * Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.linecorp.armeria.internal.dns;

import static com.linecorp.armeria.internal.dns.IPHlpAPI.AF_UNSPEC;
import static com.linecorp.armeria.internal.dns.IPHlpAPI.GAA_FLAG_SKIP_ANYCAST;
import static com.linecorp.armeria.internal.dns.IPHlpAPI.GAA_FLAG_SKIP_FRIENDLY_NAME;
import static com.linecorp.armeria.internal.dns.IPHlpAPI.GAA_FLAG_SKIP_MULTICAST;
import static com.linecorp.armeria.internal.dns.IPHlpAPI.GAA_FLAG_SKIP_UNICAST;
import static com.linecorp.armeria.internal.dns.IPHlpAPI.INSTANCE;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.ptr.IntByReference;

import com.linecorp.armeria.internal.dns.IPHlpAPI.IP_ADAPTER_ADDRESSES_LH;
import com.linecorp.armeria.internal.dns.IPHlpAPI.IP_ADAPTER_DNS_SERVER_ADDRESS_XP;

import io.netty.resolver.dns.DnsServerAddressStream;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.resolver.dns.DnsServerAddresses;

/**
 * Provider of DNS servers for the current system for use on Windows. The Netty default uses standard JDK
 * methods for retrieving servers, which are implemented using very old APIs and can result in issues.
 *
 * <p>TODO(anuraaga): Remove after https://github.com/netty/netty/issues/9796
 */
public class WindowsDnsServerAddressStreamProvider implements DnsServerAddressStreamProvider {

    public static DnsServerAddressStreamProvider get() {
        return PROVIDER;
    }

    private static final Logger logger = LoggerFactory.getLogger(WindowsDnsServerAddressStreamProvider.class);

    private static final WindowsDnsServerAddressStreamProvider PROVIDER =
            new WindowsDnsServerAddressStreamProvider();

    // Fallback provider
    private static final DnsServerAddressStreamProvider DEFAULT_PROVIDER =
            DnsServerAddressStreamProviders.platformDefault();

    // Let's refresh every 10 seconds.
    private static final long REFRESH_INTERVAL = TimeUnit.SECONDS.toNanos(10);

    @Nullable
    private volatile DnsServerAddresses currentServers = retrieveCurrentServers();
    private final AtomicLong lastRefresh = new AtomicLong(System.nanoTime());

    @Override
    public DnsServerAddressStream nameServerAddressStream(String hostname) {
        final long last = lastRefresh.get();
        DnsServerAddresses servers = currentServers;
        if (System.nanoTime() - last > REFRESH_INTERVAL) {
            // This is slightly racy which means it will be possible still use the old configuration for a small
            // amount of time, but that's ok.
            if (lastRefresh.compareAndSet(last, System.nanoTime())) {
                servers = currentServers = retrieveCurrentServers();
            }
        }
        if (servers == null) {
            return DEFAULT_PROVIDER.nameServerAddressStream(hostname);
        }
        return servers.stream();
    }

    private DnsServerAddresses retrieveCurrentServers() {
        // The recommended method of calling the GetAdaptersAddresses function is to pre-allocate a
        // 15KB working buffer
        Memory buffer = new Memory(15 * 1024);
        final IntByReference size = new IntByReference(0);
        final int flags =
                GAA_FLAG_SKIP_UNICAST |
                GAA_FLAG_SKIP_ANYCAST |
                GAA_FLAG_SKIP_MULTICAST |
                GAA_FLAG_SKIP_FRIENDLY_NAME;
        int error = INSTANCE.GetAdaptersAddresses(AF_UNSPEC, flags, Pointer.NULL, buffer, size);
        if (error == WinError.ERROR_BUFFER_OVERFLOW) {
            buffer = new Memory(size.getValue());
            error = INSTANCE.GetAdaptersAddresses(AF_UNSPEC, flags, Pointer.NULL, buffer, size);
            if (error != WinError.ERROR_SUCCESS) {
                return null;
            }
        }

        final List<InetSocketAddress> servers = new ArrayList<>();
        IP_ADAPTER_ADDRESSES_LH result = new IP_ADAPTER_ADDRESSES_LH(buffer);
        do {
            // only interfaces with IfOperStatusUp
            if (result.OperStatus == 1) {
                IP_ADAPTER_DNS_SERVER_ADDRESS_XP dns = result.FirstDnsServerAddress;
                while (dns != null) {
                    InetAddress address = null;
                    try {
                        address = dns.Address.toAddress();
                        if (!address.isSiteLocalAddress()) {
                            servers.add(new InetSocketAddress(address, 53 /* default DNS port */));
                        } else {
                            logger.debug(
                                    "Skipped site-local IPv6 server address {} on adapter index {}",
                                    address,
                                    result.IfIndex);
                        }
                    } catch (UnknownHostException e) {
                        logger.warn("Invalid nameserver address on adapter index {}", result.IfIndex, e);
                    }

                    dns = dns.Next;
                }
            }

            result = result.Next;
        } while (result != null);
        return DnsServerAddresses.sequential(servers);
    }
}
