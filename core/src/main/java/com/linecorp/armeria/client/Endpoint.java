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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import com.google.common.net.InternetDomainName;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.util.NetUtil;

/**
 * A remote endpoint that refers to a single host.
 *
 * <p>An endpoint has {@link #host()}, optional {@link #ipAddr()} and optional {@link #port()}. It can be
 * represented as {@code "<host>"} or {@code "<host>:<port>"} in the authority part of a URI. It can have
 * an IP address if the host name has been resolved and thus there's no need to query a DNS server.</p>
 */
public final class Endpoint implements Comparable<Endpoint>, EndpointGroup {

    private static final Comparator<Endpoint> COMPARATOR =
            Comparator.comparing(Endpoint::host)
                      .thenComparing(e -> e.ipAddr, Comparator.nullsFirst(Comparator.naturalOrder()))
                      .thenComparing(e -> e.port);

    private static final int DEFAULT_WEIGHT = 1000;

    /**
     * Validator for the scheme part of the URI, as defined in
     * <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-3.1">the section 3.1 of RFC3986</a>.
     */
    private static final Predicate<String> SCHEME_VALIDATOR =
            scheme -> Pattern.compile("^([a-z][a-z0-9+\\-.]*)").matcher(scheme).matches();

    private static final Cache<String, Endpoint> cache =
            Caffeine.newBuilder()
                    .maximumSize(8192) // TODO(ikhoon): Add a flag if there is a demand for it.
                    .build();

    /**
     * Parse the authority part of a URI. The authority part may have one of the following formats:
     * <ul>
     *   <li>{@code "<host>:<port>"} for a host endpoint (The userinfo part will be ignored.)</li>
     *   <li>{@code "<host>"} for a host endpoint with no port number specified</li>
     * </ul>
     * An IPv4 or IPv6 address can be specified in lieu of a host name, e.g. {@code "127.0.0.1:8080"} and
     * {@code "[::1]:8080"}.
     */
    public static Endpoint parse(String authority) {
        requireNonNull(authority, "authority");
        checkArgument(!authority.isEmpty(), "authority is empty");
        return cache.get(authority, key -> {
            if (key.charAt(key.length() - 1) == ':') {
                // HostAndPort.fromString() does not validate an authority that ends with ':' such as "0.0.0.0:"
                throw new IllegalArgumentException("Missing port number: " + key);
            }
            final HostAndPort hostAndPort = HostAndPort.fromString(removeUserInfo(key)).withDefaultPort(0);
            return create(hostAndPort.getHost(), hostAndPort.getPort(), true);
        });
    }

    /**
     * Creates a new host {@link Endpoint}.
     *
     * @throws IllegalArgumentException if {@code host} is not a valid host name or
     *                                  {@code port} is not a valid port number
     */
    public static Endpoint of(String host, int port) {
        validatePort("port", port);
        return create(host, port, true);
    }

    /**
     * Creates a new host {@link Endpoint} with unspecified port number.
     *
     * @throws IllegalArgumentException if {@code host} is not a valid host name
     */
    public static Endpoint of(String host) {
        return create(host, 0, true);
    }

    /**
     * Creates a new host {@link Endpoint} <strong>without</strong> validation.
     *
     * <p>Note that you should carefully use this method only when both {@code host} and {@code port} are
     * already valid.
     */
    @UnstableApi
    public static Endpoint unsafeCreate(String host, int port) {
        return create(host, port, /* validateHost */ false);
    }

    private static Endpoint create(String host, int port, boolean validateHost) {
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

        if (validateHost) {
            host = InternetDomainName.from(host).toString();
        }
        return new Endpoint(host, null, port, DEFAULT_WEIGHT, HostType.HOSTNAME_ONLY);
    }

    private static String removeUserInfo(String authority) {
        final int indexOfDelimiter = authority.lastIndexOf('@');
        if (indexOfDelimiter == -1) {
            return authority;
        }
        return authority.substring(indexOfDelimiter + 1);
    }

    private enum HostType {
        HOSTNAME_ONLY,
        HOSTNAME_AND_IPv4,
        HOSTNAME_AND_IPv6,
        IPv4_ONLY,
        IPv6_ONLY
    }

    private final String host;
    @Nullable
    private final String ipAddr;
    private final int port;
    private final int weight;
    private final List<Endpoint> endpoints;
    private final HostType hostType;
    private final String authority;
    private final String strVal;

    @Nullable
    private CompletableFuture<Endpoint> selectFuture;
    @Nullable
    private CompletableFuture<List<Endpoint>> whenReadyFuture;
    private int hashCode;

    private Endpoint(String host, @Nullable String ipAddr, int port, int weight, HostType hostType) {
        this.host = host;
        this.ipAddr = ipAddr;
        this.port = port;
        this.weight = weight;
        this.hostType = hostType;

        endpoints = ImmutableList.of(this);

        // hostType must be HOSTNAME_ONLY when ipAddr is null and vice versa.
        assert ipAddr == null && hostType == HostType.HOSTNAME_ONLY ||
               ipAddr != null && hostType != HostType.HOSTNAME_ONLY;

        // Pre-generate the authority.
        authority = generateAuthority(host, port, hostType);
        // Pre-generate toString() value.
        strVal = generateToString(authority, ipAddr, weight, hostType);
    }

    private static String generateAuthority(String host, int port, HostType hostType) {
        if (port != 0) {
            if (hostType == HostType.IPv6_ONLY) {
                return '[' + host + "]:" + port;
            } else {
                return host + ':' + port;
            }
        }

        if (hostType == HostType.IPv6_ONLY) {
            return '[' + host + ']';
        } else {
            return  host;
        }
    }

    private static String generateToString(String authority, @Nullable String ipAddr,
                                           int weight, HostType hostType) {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append("Endpoint{").append(authority);
            if (hostType == HostType.HOSTNAME_AND_IPv4 ||
                hostType == HostType.HOSTNAME_AND_IPv6) {
                buf.append(", ipAddr=").append(ipAddr);
            }
            return buf.append(", weight=").append(weight).append('}').toString();
        }
    }

    @Override
    public List<Endpoint> endpoints() {
        return endpoints;
    }

    @Override
    public void addListener(Consumer<? super List<Endpoint>> listener, boolean notifyLatestEndpoints) {
        if (notifyLatestEndpoints) {
            // Endpoint will notify only once when a listener is attached.
            listener.accept(endpoints);
        }
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return EndpointSelectionStrategy.weightedRoundRobin();
    }

    @Override
    public Endpoint selectNow(ClientRequestContext ctx) {
        return this;
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                              ScheduledExecutorService executor,
                                              long timeoutMillis) {
        if (selectFuture == null) {
            selectFuture = UnmodifiableFuture.completedFuture(this);
        }
        return selectFuture;
    }

    @Override
    public CompletableFuture<List<Endpoint>> whenReady() {
        if (whenReadyFuture == null) {
            whenReadyFuture = UnmodifiableFuture.completedFuture(endpoints);
        }
        return whenReadyFuture;
    }

    /**
     * Returns the host name of this endpoint.
     */
    public String host() {
        return host;
    }

    /**
     * Returns the IP address of this endpoint.
     *
     * @return the IP address, or {@code null} if the host name is not resolved yet
     */
    @Nullable
    public String ipAddr() {
        return ipAddr;
    }

    /**
     * Returns whether this endpoint has an IP address resolved. This method is a shortcut for
     * {@code ipAddr() != null}.
     *
     * @return {@code true} if and only if this endpoint has an IP address.
     */
    public boolean hasIpAddr() {
        return ipAddr() != null;
    }

    /**
     * Returns whether this endpoint's host name is an IP address.
     *
     * @return {@code true} if and only if this endpoint's host name is an IP address
     */
    public boolean isIpAddrOnly() {
        return hostType == HostType.IPv4_ONLY || hostType == HostType.IPv6_ONLY;
    }

    /**
     * Returns the {@link StandardProtocolFamily} of this endpoint's IP address.
     *
     * @return the {@link StandardProtocolFamily} of this endpoint's IP address, or
     *         {@code null} if the host name is not resolved yet
     */
    @Nullable
    public StandardProtocolFamily ipFamily() {
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
     * @throws IllegalStateException this endpoint does not have its port specified.
     */
    public int port() {
        if (port == 0) {
            throw new IllegalStateException("port not specified");
        }
        return port;
    }

    /**
     * Returns the port number of this endpoint.
     *
     * @param defaultValue the default value to return when this endpoint does not have its port specified
     */
    public int port(int defaultValue) {
        return port != 0 ? port : defaultValue;
    }

    /**
     * Returns whether this endpoint has a port number specified.
     *
     * @return {@code true} if and only if this endpoint has a port number.
     */
    public boolean hasPort() {
        return port != 0;
    }

    /**
     * Returns a new host endpoint with the specified port number.
     *
     * @param port the new port number
     * @return the new endpoint with the specified port number if this endpoint does not have a port or
     *         it has a different port number than what's specified.
     *         {@code this} if this endpoint has the same port number with the specified one.
     */
    public Endpoint withPort(int port) {
        validatePort("port", port);
        if (this.port == port) {
            return this;
        }
        return new Endpoint(host, ipAddr, port, weight, hostType);
    }

    /**
     * Returns a new host endpoint with its port number unspecified.
     *
     * @return the new endpoint whose port is unspecified if this endpoint has its port.
     *         {@code this} if this endpoint does not have a port already.
     *
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    public Endpoint withoutPort() {
        if (port == 0) {
            return this;
        }
        return new Endpoint(host, ipAddr, 0, weight, hostType);
    }

    /**
     * Returns a new host endpoint with the specified default port number.
     *
     * @param defaultPort the default port number
     * @return the new endpoint whose port is {@code defaultPort} if this endpoint does not have its port
     *         specified. {@code this} if this endpoint already has its port specified.
     *
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    public Endpoint withDefaultPort(int defaultPort) {
        validatePort("defaultPort", defaultPort);

        if (port != 0) {
            return this;
        }

        return new Endpoint(host, ipAddr, defaultPort, weight, hostType);
    }

    /**
     * Returns a new host endpoint with the default port number removed.
     *
     * @param defaultPort the default port number
     * @return the new endpoint without a port number if this endpoint had the same port number
     *         with the specified default port number. {@code this} if this endpoint had a different
     *         port number than the specified default port number or this endpoint already does not have
     *         a port number.
     *
     * @throws IllegalStateException if this endpoint is not a host but a group
     */
    public Endpoint withoutDefaultPort(int defaultPort) {
        validatePort("defaultPort", defaultPort);
        if (port == defaultPort) {
            return new Endpoint(host, ipAddr, 0, weight, hostType);
        }
        return this;
    }

    /**
     * Returns a new host endpoint with the specified IP address.
     *
     * @return the new endpoint with the specified IP address.
     *         {@code this} if this endpoint has the same IP address.
     *
     * @throws IllegalArgumentException if the specified IP address is invalid
     */
    public Endpoint withIpAddr(@Nullable String ipAddr) {
        if (ipAddr == null) {
            return withoutIpAddr();
        }

        if (ipAddr.equals(this.ipAddr)) {
            return this;
        }

        if (NetUtil.isValidIpV4Address(ipAddr)) {
            return withIpAddr(ipAddr, StandardProtocolFamily.INET);
        }

        if (NetUtil.isValidIpV6Address(ipAddr)) {
            final boolean wrappedByBracket = ipAddr.charAt(0) == '[';
            final int percentIdx = ipAddr.indexOf('%');
            if (percentIdx < 0) {
                if (wrappedByBracket) {
                    ipAddr = ipAddr.substring(1, ipAddr.length() - 1);
                }
            } else {
                ipAddr = ipAddr.substring(wrappedByBracket ? 1 : 0, percentIdx);
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

    /**
     * Returns a new host endpoint with the {@linkplain InetAddress#getHostAddress() IP address} of
     * the specified {@link InetAddress}.
     *
     * @return the new endpoint with the specified {@link InetAddress}.
     *         {@code this} if this endpoint has the same IP address.
     */
    public Endpoint withInetAddress(InetAddress address) {
        requireNonNull(address, "address");
        final String ipAddr = address.getHostAddress();
        if (address instanceof Inet4Address) {
            return withIpAddr(ipAddr, StandardProtocolFamily.INET);
        } else if (address instanceof Inet6Address) {
            return withIpAddr(ipAddr, StandardProtocolFamily.INET6);
        } else {
            return withIpAddr(ipAddr);
        }
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
        return weight;
    }

    /**
     * Converts this endpoint into the authority part of a URI.
     *
     * @return the authority string
     */
    public String authority() {
        return authority;
    }

    /**
     * Converts this endpoint into a URI using the {@code scheme}.
     *
     * @param scheme the {@code scheme} for {@link URI}.
     *
     * @return the URI
     */
    public URI toUri(String scheme) {
        requireNonNull(scheme, "scheme");

        return toUri(scheme, null);
    }

    /**
     * Converts this endpoint into a URI using the {@code scheme} and {@code path}.
     *
     * @param scheme the {@code scheme} for {@link URI}.
     * @param path the {@code path} for {@link URI}.
     *
     * @return the URI
     */
    public URI toUri(String scheme, @Nullable String path) {
        requireNonNull(scheme, "scheme");

        if (!SCHEME_VALIDATOR.test(scheme)) {
            throw new IllegalArgumentException("scheme: " + scheme + " (expected: a valid scheme)");
        }

        try {
            return new URI(scheme, authority, path, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Converts this endpoint into a URI using the {@link SessionProtocol}.
     *
     * @param sessionProtocol the {@link SessionProtocol} for {@link URI}.
     *
     * @return the URI
     */
    public URI toUri(SessionProtocol sessionProtocol) {
        requireNonNull(sessionProtocol, "sessionProtocol");

        return toUri(sessionProtocol, null);
    }

    /**
     * Converts this endpoint into a URI using the {@link SessionProtocol} and {@code path}.
     *
     * @param sessionProtocol the {@link SessionProtocol} for {@link URI}.
     * @param path the {@code path} for {@link URI}.
     *
     * @return the URI
     */
    public URI toUri(SessionProtocol sessionProtocol, @Nullable String path) {
        requireNonNull(sessionProtocol, "sessionProtocol");

        return toUri(Scheme.of(SerializationFormat.NONE, sessionProtocol), path);
    }

    /**
     * Converts this endpoint into a URI using the {@link Scheme}.
     *
     * @param scheme the {@link Scheme} for {@link URI}.
     *
     * @return the URI
     */
    public URI toUri(Scheme scheme) {
        requireNonNull(scheme, "scheme");
        return toUri(scheme, null);
    }

    /**
     * Converts this endpoint into a URI using the {@link Scheme} and the {@code path}.
     *
     * @param scheme the {@link Scheme} for {@link URI}.
     * @param path the {@code path} for {@link URI}.
     *
     * @return the URI
     */
    public URI toUri(Scheme scheme, @Nullable String path) {
        requireNonNull(scheme, "scheme");
        try {
            return new URI(scheme.uriText(), authority, path, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static void validatePort(String name, int port) {
        checkArgument(port > 0 && port <= 65535, "%s: %s (expected: 1-65535)", name, port);
    }

    private static void validateWeight(int weight) {
        checkArgument(weight >= 0, "weight: %s (expected: >= 0)", weight);
    }

    // Methods from Auto/AsyncCloseable

    /**
     * This method does nothing but returning an immediately complete future.
     */
    @Override
    public CompletableFuture<?> closeAsync() {
        return UnmodifiableFuture.completedFuture(null);
    }

    /**
     * This method does nothing.
     */
    @Override
    public void close() {}

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof Endpoint)) {
            return false;
        }

        final Endpoint that = (Endpoint) obj;
        return host().equals(that.host()) &&
               Objects.equals(ipAddr, that.ipAddr) &&
               port == that.port;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = (host.hashCode() * 31 + Objects.hashCode(ipAddr)) * 31 + port;
        }
        return hashCode;
    }

    @Override
    public int compareTo(Endpoint that) {
        return COMPARATOR.compare(this, that);
    }

    @Override
    public String toString() {
        return strVal;
    }
}
