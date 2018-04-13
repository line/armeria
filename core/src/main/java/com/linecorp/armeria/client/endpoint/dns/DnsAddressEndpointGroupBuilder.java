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

import com.linecorp.armeria.client.Endpoint;

import io.netty.resolver.ResolvedAddressTypes;
import io.netty.util.NetUtil;

/**
 * Builds a new {@link DnsAddressEndpointGroup} that sources its {@link Endpoint} list from the {@code A} or
 * {@code AAAA} DNS records of a certain hostname.
 */
public final class DnsAddressEndpointGroupBuilder
        extends DnsEndpointGroupBuilder<DnsAddressEndpointGroupBuilder> {

    private int port;
    private boolean ipV4Enabled = true;
    private boolean ipV6Enabled = NetUtil.isIpV4StackPreferred();

    /**
     * Creates a new instance that builds a {@link DnsAddressEndpointGroup} for the specified {@code hostname}.
     */
    public DnsAddressEndpointGroupBuilder(String hostname) {
        super(hostname);
        port = 0;
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

    ResolvedAddressTypes resolvedAddressTypes() {
        if (ipV4Enabled) {
            if (ipV6Enabled) {
                return ResolvedAddressTypes.IPV4_PREFERRED;
            } else {
                return ResolvedAddressTypes.IPV4_ONLY;
            }
        } else {
            if (ipV6Enabled) {
                return ResolvedAddressTypes.IPV6_ONLY;
            } else {
                throw new IllegalStateException("You cannot disable both IPv4 and IPv6.");
            }
        }
    }

    /**
     * Sets whether IPv4 addresses are enabled. If enabled, {@code A} records are retrieved from DNS servers
     * and IPv4 addresses will be included in {@link Endpoint}s. IPv4 addresses are enabled by default.
     */
    public DnsAddressEndpointGroupBuilder ipV4Enabled(boolean ipV4Enabled) {
        this.ipV4Enabled = ipV4Enabled;
        return this;
    }

    /**
     * Sets whether IPv6 addresses are enabled. If enabled, {@code AAAA} records are retrieved from DNS servers
     * and IPv6 address will be included in {@link Endpoint}s. IPv6 addresses are enabled by default unless
     * you specified {@code -Djava.net.preferIPv4Stack=true} JVM option.
     */
    public DnsAddressEndpointGroupBuilder ipV6Enabled(boolean ipV6Enabled) {
        this.ipV6Enabled = ipV6Enabled;
        return this;
    }

    /**
     * Returns a newly created {@link DnsAddressEndpointGroup}.
     */
    public DnsAddressEndpointGroup build() {
        return new DnsAddressEndpointGroup(eventLoop(), minTtl(), maxTtl(),
                                           serverAddressStreamProvider(), backoff(),
                                           resolvedAddressTypes(), hostname(), port);
    }
}
