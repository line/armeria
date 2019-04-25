/*
 * Copyright 2016 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.StandardProtocolFamily;
import java.util.Comparator;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.net.HostAndPort;
import com.google.common.net.InternetDomainName;

import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;

import io.netty.util.NetUtil;

/**
 * A remote endpoint that refers to a single host or a group of multiple hosts.
 *
 * <p>A host endpoint has {@link #host()}, optional {@link #ipAddr()} and optional {@link #port()}. It can be
 * represented as {@code "<host>"} or {@code "<host>:<port>"} in the authority part of a URI. It can have
 * an IP address if the host name has been resolved and thus there's no need to query a DNS server.
 *
 * <p>A group endpoint has {@link #groupName()} and it can be represented as {@code "group:<groupName>"}
 * in the authority part of a URI. It can be resolved into a host endpoint with
 * {@link #resolve(ClientRequestContext)}.
 */
public final class Endpoint implements Comparable<Endpoint> {

    private static final Comparator<Endpoint> NON_GROUP_COMPARATOR =
            Comparator.comparing(Endpoint::host)
                      .thenComparing(e -> e.ipAddr, Comparator.nullsFirst(Comparator.naturalOrder()))
                      .thenComparing(e -> e.port);

    private static final int DEFAULT_WEIGHT = 1000;

    /**
     * Parse the authority part of a URI. The authority part may have one of the following formats:
     * <ul>
     *   <li>{@code "group:<groupName>"} for a group endpoint</li>
     *   <li>{@code "<host>:<port>"} for a host endpoint</li>
     *   <li>{@code "<host>"} for a host endpoint with no port number specified</li>
     * </ul>
     * An IPv4 or IPv6 address can be specified in lieu of a host name, e.g. {@code "127.0.0.1:8080"} and
     * {@code "[::1]:8080"}.
     */
    public static Endpoint parse(String authority) {
        requireNonNull(authority, "authority");
        if (authority.startsWith("group:")) {
            return ofGroup(authority.substring(6));
        }

        final HostAndPort parsed = HostAndPort.fromString(authority).withDefaultPort(0);
        return create(parsed.getHost(), parsed.getPort());
    }

    /**
     * Creates a new group {@link Endpoint}.
     */
    public static Endpoint ofGroup(String name) {
        requireNonNull(name, "name");
        return new Endpoint(name);
    }

    /**
     * Creates a new host {@link Endpoint}.
     *
     * @throws IllegalArgumentException if {@code host} is not a valid host name or
     *                                  {@code port} is not a valid port number
     */
    public static Endpoint of(String host, int port) {
        validatePort("port", port);
        return create(host, port);
    }

    /**
     * Creates a new host {@link Endpoint} with unspecified port number.
     *
     * @throws IllegalArgumentException if {@code host} is not a valid host name
     */
    public static Endpoint of(String host) {
        return create(host, 0);
    }

    // TODO(trustin): Remove weight and make Endpoint a pure endpoint representation.
    //                We could specify an additional attributes such as weight/priority
    //                when adding an Endpoint to an EndpointGroup.

    /**
     * Creates a new host {@link Endpoint}.
     *
     * @deprecated Use {@link #of(String, int)} and {@link #withWeight(int)},
     *             e.g. {@code Endpoint.of("foo.com", 80).withWeight(500)}.
     */
    @Deprecated
    public static Endpoint of(String host, int port, int weight) {
        return of(host, port).withWeight(weight);
    }

    private static Endpoint create(String host, int port) {
        requireNonNull(host, "host");

        if (NetUtil.isValidIpV4Address(host)) {
            return new Endpoint(host, host, port, DEFAULT_WEIGHT, HostType.IPv4_ONLY);
        }

        if (NetUtil.isValidIpV6Address(host)) {
            final String ipV6Addr;
            if (host.charAt(0) == '[') {
                // Strip surrounding '[' and ']'.
                ipV6Addr = host.substring(1, host.length() - 1);
            } else {
                ipV6Addr = host;
            }
            return new Endpoint(ipV6Addr, ipV6Addr, port, DEFAULT_WEIGHT, HostType.IPv6_ONLY);
        }

        return new Endpoint(InternetDomainName.from(host).toString(),
                            null, port, DEFAULT_WEIGHT, HostType.HOSTNAME_ONLY);
    }

    private enum HostType {
        HOSTNAME_ONLY,
        HOSTNAME_AND_IPv4,
        HOSTNAME_AND_IPv6,
        IPv4_ONLY,
        IPv6_ONLY
    }

    @Nullable
    private final String groupName;
    @Nullable
    private final String host;
    @Nullable
    private final String ipAddr;
    private final int port;
    private final int weight;
    @Nullable // null if this endpoint is a group.
    private final HostType hostType;
    @Nullable
    private String authority;

    private Endpoint(String groupName) {
        this.groupName = groupName;
        host = null;
        ipAddr = null;
        port = 0;
        weight = 0;
        hostType = null;
    }

    private Endpoint(String host, @Nullable String ipAddr, int port, int weight, HostType hostType) {
        this.host = host;
        this.ipAddr = ipAddr;
        this.port = port;
        this.weight = weight;
        this.hostType = hostType;
        groupName = null;

        // hostType must be HOSTNAME_ONLY when ipAddr is null and vice versa.
        assert ipAddr == null && hostType == HostType.HOSTNAME_ONLY ||
               ipAddr != null && hostType != HostType.HOSTNAME_ONLY;
    }

    /**
     * Returns {@code true} if this endpoint refers to a group.
     */
    public boolean isGroup() {
        return groupName != null;
    }

    /**
     * Resolves this endpoint into a host endpoint associated with the specified
     * {@link ClientRequestContext}.
     *
     * @return the {@link Endpoint} resolved by {@link EndpointGroupRegistry}.
     *         {@code this} if this endpoint is already a host endpoint.
     */
    public Endpoint resolve(ClientRequestContext ctx) {
        if (isGroup()) {
            return EndpointGroupRegistry.selectNode(ctx, groupName);
        } else {
            return this;
        }
    }

    /**
     * Returns the group name of this endpoint.
     *
     * @throws IllegalStateException if this endpoint is not a group but a host
     */
    public String groupName() {
        ensureGroup();
        return groupName;
    }

    /**
     * Returns the host name of this endpoint.
     *
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    public String host() {
        ensureSingle();
        return host;
    }

    /**
     * Returns the IP address of this endpoint.
     *
     * @return the IP address, or {@code null} if the host name is not resolved yet
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    @Nullable
    public String ipAddr() {
        ensureSingle();
        return ipAddr;
    }

    /**
     * Returns whether this endpoint has an IP address resolved. This method is a shortcut of
     * {@code ipAddr() != null}.
     *
     * @return {@code true} if and only if this endpoint has an IP address.
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    public boolean hasIpAddr() {
        return ipAddr() != null;
    }

    /**
     * Returns whether this endpoint's host name is an IP address.
     *
     * @return {@code true} if and only if this endpoint's host name is an IP address
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    public boolean isIpAddrOnly() {
        ensureSingle();
        return hostType == HostType.IPv4_ONLY || hostType == HostType.IPv6_ONLY;
    }

    /**
     * Returns the {@link StandardProtocolFamily} of this endpoint's IP address.
     *
     * @return the {@link StandardProtocolFamily} of this endpoint's IP address, or
     *         {@code null} if the host name is not resolved yet
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    @Nullable
    public StandardProtocolFamily ipFamily() {
        ensureSingle();
        switch (hostType) {
            case HOSTNAME_AND_IPv4:
            case IPv4_ONLY:
                return StandardProtocolFamily.INET;
            case HOSTNAME_AND_IPv6:
            case IPv6_ONLY:
                return StandardProtocolFamily.INET6;
            default:
                return null;
        }
    }

    /**
     * Returns the port number of this endpoint.
     *
     * @throws IllegalStateException if this endpoint is not a host but a group, or
     *                               this endpoint does not have its port specified.
     */
    public int port() {
        ensureSingle();
        if (port == 0) {
            throw new IllegalStateException("port not specified");
        }
        return port;
    }

    /**
     * Returns the port number of this endpoint.
     *
     * @param defaultValue the default value to return when this endpoint does not have its port specified
     *
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    public int port(int defaultValue) {
        ensureSingle();
        return port != 0 ? port : defaultValue;
    }

    /**
     * Returns a new host endpoint with the specified default port number.
     *
     * @return the new endpoint whose port is {@code defaultPort} if this endpoint does not have its port
     *         specified. {@code this} if this endpoint already has its port specified.
     *
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    public Endpoint withDefaultPort(int defaultPort) {
        ensureSingle();
        validatePort("defaultPort", defaultPort);

        if (port != 0) {
            return this;
        }

        return new Endpoint(host(), ipAddr(), defaultPort, weight(), hostType);
    }

    /**
     * Returns a new host endpoint with the specified IP address.
     *
     * @return the new endpoint with the specified IP address.
     *         {@code this} if this endpoint has the same IP address.
     *
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    public Endpoint withIpAddr(@Nullable String ipAddr) {
        ensureSingle();
        if (ipAddr == null) {
            return withoutIpAddr();
        }

        if (NetUtil.isValidIpV4Address(ipAddr)) {
            return withIpAddr(ipAddr, StandardProtocolFamily.INET);
        }

        if (NetUtil.isValidIpV6Address(ipAddr)) {
            if (ipAddr.charAt(0) == '[') {
                ipAddr = ipAddr.substring(1, ipAddr.length() - 1);
            }
            return withIpAddr(ipAddr, StandardProtocolFamily.INET6);
        }

        throw new IllegalArgumentException("ipAddr: " + ipAddr + " (expected: an IP address)");
    }

    private Endpoint withIpAddr(String ipAddr, StandardProtocolFamily ipFamily) {
        if (ipAddr.equals(this.ipAddr)) {
            return this;
        }

        // Replace the host name as well if the host name is an IP address.
        if (isIpAddrOnly()) {
            return new Endpoint(ipAddr, ipAddr, port, weight,
                                ipFamily == StandardProtocolFamily.INET ? HostType.IPv4_ONLY
                                                                        : HostType.IPv6_ONLY);
        }

        return new Endpoint(host(), ipAddr, port, weight,
                            ipFamily == StandardProtocolFamily.INET ? HostType.HOSTNAME_AND_IPv4
                                                                    : HostType.HOSTNAME_AND_IPv6);
    }

    private Endpoint withoutIpAddr() {
        if (ipAddr == null) {
            return this;
        }
        if (isIpAddrOnly()) {
            throw new IllegalStateException("can't clear the IP address if host name is an IP address: " +
                                            this);
        }
        return new Endpoint(host(), null, port, weight, HostType.HOSTNAME_ONLY);
    }

    /**
     * Returns a new host endpoint with the specified weight.
     *
     * @return the new endpoint with the specified weight. {@code this} if this endpoint has the same weight.
     *
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    public Endpoint withWeight(int weight) {
        ensureSingle();
        validateWeight(weight);
        if (this.weight == weight) {
            return this;
        }
        return new Endpoint(host(), ipAddr(), port, weight, hostType);
    }

    /**
     * Returns the weight of this endpoint.
     */
    public int weight() {
        ensureSingle();
        return weight;
    }

    /**
     * Converts this endpoint into the authority part of a URI.
     *
     * @return the authority string
     */
    public String authority() {
        String authority = this.authority;
        if (authority != null) {
            return authority;
        }

        if (isGroup()) {
            authority = "group:" + groupName;
        } else if (port != 0) {
            if (hostType == HostType.IPv6_ONLY) {
                authority = '[' + host() + "]:" + port;
            } else {
                authority = host() + ':' + port;
            }
        } else if (hostType == HostType.IPv6_ONLY) {
            authority = '[' + host() + ']';
        } else {
            authority = host();
        }

        return this.authority = authority;
    }

    /**
     * Converts this endpoint into a URI string.
     *
     * @return the URI string
     */
    public String toURI() {
        final String authority;

        if (ipAddr == null) {
            authority = authority();
        } else if (port == 0) {
            if (NetUtil.isValidIpV4Address(ipAddr)) {
                authority = ipAddr;
            } else {
                authority = '[' + ipAddr + ']';
            }
        } else {
            if (NetUtil.isValidIpV4Address(ipAddr)) {
                authority = ipAddr + ':' + port;
            } else {
                authority = '[' + ipAddr + "]:" + port;
            }
        }
        return "http://" + authority;
    }

    private void ensureGroup() {
        if (!isGroup()) {
            throw new IllegalStateException("not a group endpoint");
        }
    }

    private void ensureSingle() {
        if (isGroup()) {
            throw new IllegalStateException("not a host:port endpoint");
        }
    }

    private static void validatePort(String name, int port) {
        checkArgument(port > 0 && port <= 65535, "%s: %s (expected: 1-65535)", name, port);
    }

    private static void validateWeight(int weight) {
        checkArgument(weight >= 0, "weight: %s (expected: >= 0)", weight);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof Endpoint)) {
            return false;
        }

        final Endpoint that = (Endpoint) obj;
        if (isGroup()) {
            if (that.isGroup()) {
                return groupName().equals(that.groupName());
            } else {
                return false;
            }
        } else {
            if (that.isGroup()) {
                return false;
            } else {
                return host().equals(that.host()) &&
                       Objects.equals(ipAddr, that.ipAddr) &&
                       port == that.port;
            }
        }
    }

    @Override
    public int hashCode() {
        return (authority().hashCode() * 31 + Objects.hashCode(ipAddr)) * 31 + port;
    }

    @Override
    public int compareTo(Endpoint that) {
        if (isGroup()) {
            if (that.isGroup()) {
                return groupName().compareTo(that.groupName());
            } else {
                return -1;
            }
        } else {
            if (that.isGroup()) {
                return 1;
            } else {
                return NON_GROUP_COMPARATOR.compare(this, that);
            }
        }
    }

    @Override
    public String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this);
        helper.addValue(authority());
        if (!isGroup()) {
            if (hostType == HostType.HOSTNAME_AND_IPv4 ||
                hostType == HostType.HOSTNAME_AND_IPv6) {
                helper.add("ipAddr", ipAddr);
            }
            helper.add("weight", weight);
        }
        return helper.toString();
    }
}
