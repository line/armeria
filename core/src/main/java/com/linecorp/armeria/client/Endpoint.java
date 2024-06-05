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
import static com.linecorp.armeria.internal.common.util.DomainSocketUtil.DOMAIN_SOCKET_IP;
import static com.linecorp.armeria.internal.common.util.DomainSocketUtil.DOMAIN_SOCKET_PORT;
import static java.util.Objects.requireNonNull;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.Attributes;
import com.linecorp.armeria.common.AttributesBuilder;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.SchemeAndAuthority;
import com.linecorp.armeria.internal.common.util.IpAddrUtil;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.util.AttributeKey;
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

    private static final Cache<String, Endpoint> cache =
            Caffeine.newBuilder()
                    .maximumSize(8192) // TODO(ikhoon): Add a flag if there is a demand for it.
                    .build();

    /**
     * Parse the authority part of a URI. The authority part may have one of the following formats:
     * <ul>
     *   <li>{@code "<host>:<port>"} for a host endpoint (The userinfo part will be ignored.)</li>
     *   <li>{@code "<host>"}, {@code "<host>:"} for a host endpoint with no port number specified</li>
     * </ul>
     * An IPv4 or IPv6 address can be specified in lieu of a host name, e.g. {@code "127.0.0.1:8080"} and
     * {@code "[::1]:8080"}.
     */
    public static Endpoint parse(String authority) {
        requireNonNull(authority, "authority");
        checkArgument(!authority.isEmpty(), "authority is empty");
        return cache.get(authority, key -> {
            final SchemeAndAuthority schemeAndAuthority = SchemeAndAuthority.of(null, key);
            // If the port is undefined, set to 0
            final int port = schemeAndAuthority.port() == -1 ? 0 : schemeAndAuthority.port();
            return create(schemeAndAuthority.host(), port, true);
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
     * Creates a new {@link Endpoint} from the specified {@link SocketAddress}.
     * This method converts the following address types into an endpoint:
     * <ul>
     *   <li>{@link InetSocketAddress}</li>
     *   <li>{@link DomainSocketAddress}</li>
     *   <li>{@link io.netty.channel.unix.DomainSocketAddress}</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the specified {@link SocketAddress} is not supported
     */
    @UnstableApi
    public static Endpoint of(SocketAddress addr) {
        requireNonNull(addr, "addr");
        if (addr instanceof io.netty.channel.unix.DomainSocketAddress) {
            addr = DomainSocketAddress.of((io.netty.channel.unix.DomainSocketAddress) addr);
        }

        if (addr instanceof DomainSocketAddress) {
            final DomainSocketAddress domainSocketAddr = (DomainSocketAddress) addr;
            final Endpoint endpoint = new Endpoint(Type.DOMAIN_SOCKET, domainSocketAddr.authority(),
                                                   DOMAIN_SOCKET_IP, DOMAIN_SOCKET_PORT,
                                                   DEFAULT_WEIGHT, null);
            endpoint.socketAddress = domainSocketAddr;
            return endpoint;
        }

        checkArgument(addr instanceof InetSocketAddress,
                      "unsupported address: %s", addr);

        final InetSocketAddress inetAddr = (InetSocketAddress) addr;
        final String ipAddr = inetAddr.isUnresolved() ? null : inetAddr.getAddress().getHostAddress();
        final Endpoint endpoint = of(inetAddr.getHostString(), inetAddr.getPort()).withIpAddr(ipAddr);
        if (endpoint.host.equals(inetAddr.getHostString())) {
            // Cache only when the normalized host name is the same as the original host name.
            endpoint.socketAddress = inetAddr;
        }
        return endpoint;
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
        final String normalizedIpAddr = IpAddrUtil.normalize(host);
        if (normalizedIpAddr != null) {
            return new Endpoint(Type.IP_ONLY, normalizedIpAddr, normalizedIpAddr, port, DEFAULT_WEIGHT, null);
        }

        if (isDomainSocketAuthority(host)) {
            return new Endpoint(Type.DOMAIN_SOCKET, host, DOMAIN_SOCKET_IP, DOMAIN_SOCKET_PORT,
                                DEFAULT_WEIGHT, null);
        } else {
            if (validateHost) {
                host = normalizeHost(host);
            }
            return new Endpoint(Type.HOSTNAME_ONLY, host, null, port, DEFAULT_WEIGHT, null);
        }
    }

    private static boolean isDomainSocketAuthority(String host) {
        // Return true if `host` starts with `unix%3A` or `unix%3a`.
        return host.length() > 7 &&
               host.startsWith("unix%3") &&
               Ascii.toUpperCase(host.charAt(6)) == 'A';
    }

    private static String normalizeHost(String host) {
        final boolean hasTrailingDot = hasTrailingDot(host);
        host = InternetDomainName.from(host).toString();
        // InternetDomainName.from() removes the trailing dot if exists.
        assert !hasTrailingDot(host) : host;
        if (hasTrailingDot) {
            host += '.';
        }
        return host;
    }

    private static boolean hasTrailingDot(String host) {
        return !host.isEmpty() && host.charAt(host.length() - 1) == '.';
    }

    @VisibleForTesting
    enum Type {
        HOSTNAME_ONLY,
        IP_ONLY,
        HOSTNAME_AND_IP,
        DOMAIN_SOCKET
    }

    private final Type type;
    private final String host;
    @Nullable
    private final String ipAddr;
    private final int port;
    private final int weight;
    private final List<Endpoint> endpoints;
    private final String authority;
    private final String strVal;

    @Nullable
    private final Attributes attributes;

    @Nullable
    private CompletableFuture<Endpoint> selectFuture;
    @Nullable
    private CompletableFuture<List<Endpoint>> whenReadyFuture;
    @Nullable
    private InetSocketAddress socketAddress;
    private int hashCode;

    private Endpoint(Type type, String host, @Nullable String ipAddr, int port, int weight,
                     @Nullable Attributes attributes) {
        this.type = type;
        this.host = host;
        this.ipAddr = ipAddr;
        this.port = port;
        this.weight = weight;

        endpoints = ImmutableList.of(this);

        // type must be HOSTNAME_ONLY when ipAddr is null and vice versa.
        assert ipAddr == null && type == Type.HOSTNAME_ONLY ||
               ipAddr != null && type != Type.HOSTNAME_ONLY;

        // A domain socket endpoint must have the predefined IP address and port number.
        assert type != Type.DOMAIN_SOCKET || port == DOMAIN_SOCKET_PORT && DOMAIN_SOCKET_IP.equals(ipAddr);

        // Pre-generate the authority.
        authority = generateAuthority(type, host, port);
        // Pre-generate toString() value.
        strVal = generateToString(type, authority, ipAddr, weight, attributes);
        this.attributes = attributes;
    }

    private static String generateAuthority(Type type, String host, int port) {
        switch (type) {
            case DOMAIN_SOCKET:
                return host;
            case IP_ONLY:
                if (isIpV6(host)) {
                    if (port != 0) {
                        return '[' + host + "]:" + port;
                    } else {
                        return '[' + host + ']';
                    }
                }
                // fall-through
            default:
                if (hasTrailingDot(host)) {
                    // Strip the trailing dot for the authority.
                    host = host.substring(0, host.length() - 1);
                }
                return port != 0 ? host + ':' + port : host;
        }
    }

    private static String generateToString(Type type, String authority, @Nullable String ipAddr,
                                           int weight, @Nullable Attributes attributes) {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append("Endpoint{").append(authority);
            if (type == Type.HOSTNAME_AND_IP) {
                buf.append(", ipAddr=").append(ipAddr);
            }
            buf.append(", weight=").append(weight);
            if (attributes != null) {
                buf.append(", attributes=").append(attributes);
            }
            return buf.append('}').toString();
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

    @Deprecated
    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                              ScheduledExecutorService executor,
                                              long timeoutMillis) {
        return select(ctx, executor);
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx, ScheduledExecutorService executor) {
        if (selectFuture == null) {
            selectFuture = UnmodifiableFuture.completedFuture(this);
        }
        return selectFuture;
    }

    @Override
    public long selectionTimeoutMillis() {
        return 0;
    }

    @Override
    public CompletableFuture<List<Endpoint>> whenReady() {
        if (whenReadyFuture == null) {
            whenReadyFuture = UnmodifiableFuture.completedFuture(endpoints);
        }
        return whenReadyFuture;
    }

    @VisibleForTesting
    Type type() {
        return type;
    }

    /**
     * Returns the host name of this endpoint.
     */
    public String host() {
        return host;
    }

    /**
     * Returns a new endpoint with the specified host.
     */
    @UnstableApi
    public Endpoint withHost(String host) {
        requireNonNull(host, "host");
        if (host.equals(this.host)) {
            return this;
        }

        final String normalizedIpAddr = IpAddrUtil.normalize(host);
        if (normalizedIpAddr != null) {
            return new Endpoint(Type.IP_ONLY, normalizedIpAddr, normalizedIpAddr, port,
                                weight, attributes);
        } else if (isDomainSocketAuthority(host)) {
            return new Endpoint(Type.DOMAIN_SOCKET, host, DOMAIN_SOCKET_IP, DOMAIN_SOCKET_PORT,
                                weight, attributes);
        } else {
            host = normalizeHost(host);
            return new Endpoint(ipAddr != null ? Type.HOSTNAME_AND_IP : Type.HOSTNAME_ONLY,
                                host, ipAddr, port, weight, attributes);
        }
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
        return type == Type.IP_ONLY;
    }

    /**
     * Returns the {@link StandardProtocolFamily} of this endpoint's IP address.
     *
     * @return the {@link StandardProtocolFamily} of this endpoint's IP address, or
     *         {@code null} if the host name is not resolved yet.
     */
    @Nullable
    public StandardProtocolFamily ipFamily() {
        if (ipAddr == null) {
            return null;
        }

        return isIpV6(ipAddr) ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET;
    }

    private static boolean isIpV6(String ipAddr) {
        return ipAddr.indexOf(':') >= 0;
    }

    /**
     * Returns whether this endpoint connects to a domain socket.
     */
    @UnstableApi
    public boolean isDomainSocket() {
        return type == Type.DOMAIN_SOCKET;
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

        if (this.port == port || isDomainSocket()) {
            return this;
        }

        return new Endpoint(type, host, ipAddr, port, weight, attributes);
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
        if (port == 0 || isDomainSocket()) {
            return this;
        }
        return new Endpoint(type, host, ipAddr, 0, weight, attributes);
    }

    /**
     * Returns a new host endpoint with the specified default port number.
     *
     * @param defaultPort the default port number
     * @return the new endpoint whose port is {@code defaultPort} if this endpoint does not have its port
     *         specified. {@code this} if this endpoint already has its port specified.
     */
    public Endpoint withDefaultPort(int defaultPort) {
        validatePort("defaultPort", defaultPort);

        if (port != 0) {
            return this;
        }

        return new Endpoint(type, host, ipAddr, defaultPort, weight, attributes);
    }

    /**
     * Returns a new host endpoint with the default port number of the specified {@link SessionProtocol}.
     *
     * @param protocol the {@link SessionProtocol} that will provide the default port number
     * @return the new endpoint whose port is the default port number of the specified
     *         {@link SessionProtocol} if this endpoint does not have its port specified.
     *         {@code this} if this endpoint already has its port specified.
     */
    @UnstableApi
    public Endpoint withDefaultPort(SessionProtocol protocol) {
        requireNonNull(protocol, "protocol");
        return withDefaultPort(protocol.defaultPort());
    }

    /**
     * Returns a new host endpoint with the default port number removed.
     *
     * @param defaultPort the default port number
     * @return the new endpoint without a port number if this endpoint had the same port number
     *         with the specified default port number. {@code this} if this endpoint had a different
     *         port number than the specified default port number or this endpoint already does not have
     *         a port number.
     */
    public Endpoint withoutDefaultPort(int defaultPort) {
        validatePort("defaultPort", defaultPort);
        if (isDomainSocket()) {
            // A domain socket always has the predefined port number.
            return this;
        }
        if (port == defaultPort) {
            return new Endpoint(type, host, ipAddr, 0, weight, attributes);
        }
        return this;
    }

    /**
     * Returns a new host endpoint with the default port number of the specified {@link SessionProtocol}
     * removed.
     *
     * @param protocol the {@link SessionProtocol} that will provide the default port number
     * @return the new endpoint without a port number if this endpoint had the same port number
     *         with the default port number provided by the specified {@link SessionProtocol}.
     *         {@code this} if this endpoint had a different port number than the default port number or
     *         this endpoint already does not have a port number.
     */
    @UnstableApi
    public Endpoint withoutDefaultPort(SessionProtocol protocol) {
        requireNonNull(protocol, "protocol");
        return withoutDefaultPort(protocol.defaultPort());
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
        if (isDomainSocket()) {
            // A domain socket always has the predefined IP address.
            return this;
        }
        if (ipAddr == null) {
            return withoutIpAddr();
        }

        final String normalizedIpAddr = IpAddrUtil.normalize(ipAddr);
        checkArgument(normalizedIpAddr != null, "ipAddr: %s (expected: a valid IP address)", ipAddr);
        if (normalizedIpAddr.equals(this.ipAddr)) {
            return this;
        }

        if (isIpAddrOnly()) {
            return new Endpoint(Type.IP_ONLY, normalizedIpAddr, normalizedIpAddr, port, weight, attributes);
        } else {
            return new Endpoint(Type.HOSTNAME_AND_IP, host, normalizedIpAddr, port, weight, attributes);
        }
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
        return withIpAddr(address.getHostAddress());
    }

    private Endpoint withoutIpAddr() {
        if (ipAddr == null) {
            return this;
        }

        if (isIpAddrOnly()) {
            throw new IllegalStateException("can't clear the IP address if host name is an IP address: " +
                                            this);
        }

        assert type == Type.HOSTNAME_AND_IP : type;
        return new Endpoint(Type.HOSTNAME_ONLY, host, null, port, weight, attributes);
    }

    /**
     * Returns a new host endpoint with the specified weight.
     *
     * @return the new endpoint with the specified weight. {@code this} if this endpoint has the same weight.
     */
    public Endpoint withWeight(int weight) {
        validateWeight(weight);
        if (this.weight == weight) {
            return this;
        }
        return new Endpoint(type, host, ipAddr, port, weight, attributes);
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
     * Returns the attribute value associated with the given {@link AttributeKey} of this endpoint, or
     * {@code null} if there's no value associated with this key.
     */
    @UnstableApi
    @Nullable
    public <T> T attr(AttributeKey<T> key) {
        requireNonNull(key, "key");
        if (attributes == null) {
            return null;
        }
        return attributes.attr(key);
    }

    /**
     * Returns a new host endpoint with the specified {@link AttributeKey} and value.
     *
     * @return the new endpoint with the specified {@link AttributeKey} and value. {@code this} if this
     *         endpoint has the same value with the specified {@link AttributeKey}.
     *
     */
    @UnstableApi
    public <T> Endpoint withAttr(AttributeKey<T> key, @Nullable T value) {
        requireNonNull(key, "key");
        if (attributes == null) {
            if (value == null) {
                return this;
            }
            return withAttrs(Attributes.of(key, value));
        }

        if (attributes.attr(key) == value) {
            return this;
        } else {
            final AttributesBuilder attributesBuilder = attributes.toBuilder();
            attributesBuilder.set(key, value);
            return withAttrs(attributesBuilder.build());
        }
    }

    /**
     * Returns a new {@link Endpoint} with the specified {@link Attributes}.
     * Note that the {@link #attrs()} of this {@link Endpoint} is replaced with the specified
     * {@link Attributes}.
     */
    @UnstableApi
    public Endpoint withAttrs(Attributes newAttributes) {
        requireNonNull(newAttributes, "newAttributes");
        if (attrs().isEmpty() && newAttributes.isEmpty()) {
            return this;
        }

        return new Endpoint(type, host, ipAddr, port, weight, newAttributes);
    }

    /**
     * Returns the {@link Attributes} of this endpoint, or an empty {@link Attributes} if this endpoint does not
     * have any attributes.
     */
    @UnstableApi
    public Attributes attrs() {
        if (attributes == null) {
            return Attributes.of();
        }
        return attributes;
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
        scheme = ArmeriaHttpUtil.schemeValidateAndNormalize(scheme);
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

    /**
     * Converts this endpoint into an {@link InetSocketAddress}. The specified {@code defaultPort} is used
     * if this endpoint does not have a port number. A {@link DomainSocketAddress} is returned if this
     * endpoint refers to a Unix domain socket.
     *
     * @see #hasPort()
     * @see #isDomainSocket()
     */
    @UnstableApi
    public InetSocketAddress toSocketAddress(int defaultPort) {
        final InetSocketAddress socketAddress = this.socketAddress;
        if (socketAddress != null) {
            return socketAddress;
        }

        final InetSocketAddress newSocketAddress = toSocketAddress0(defaultPort);
        if (hasPort()) {
            this.socketAddress = newSocketAddress;
        }
        return newSocketAddress;
    }

    private InetSocketAddress toSocketAddress0(int defaultPort) {
        if (isDomainSocket()) {
            final String decodedHost;
            try {
                decodedHost = URLDecoder.decode(host, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // Should never reach here.
                throw new Error(e);
            }

            assert decodedHost.startsWith("unix:") : decodedHost;
            return DomainSocketAddress.of(decodedHost.substring(5)); // Strip "unix:"
        }

        final int port = hasPort() ? this.port : defaultPort;
        if (!hasIpAddr()) {
            return InetSocketAddress.createUnresolved(host, port);
        }

        assert ipAddr != null;
        try {
            return new InetSocketAddress(
                    InetAddress.getByAddress(
                            // Let JDK use the normalized IP address as the host name.
                            isIpAddrOnly() ? null : host,
                            NetUtil.createByteArrayFromIpAddressString(ipAddr)),
                    port);
        } catch (UnknownHostException e) {
            // Should never reach here.
            throw new Error(e);
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
        return hashCode() == that.hashCode() &&
               host.equals(that.host) &&
               Objects.equals(ipAddr, that.ipAddr) &&
               port == that.port;
    }

    @Override
    public int hashCode() {
        final int hashCode = this.hashCode;
        if (hashCode != 0) {
            return hashCode;
        }

        int newHashCode = (host.hashCode() * 31 + Objects.hashCode(ipAddr)) * 31 + port;
        if (newHashCode == 0) {
            newHashCode = 1;
        }

        this.hashCode = newHashCode;
        return newHashCode;
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
