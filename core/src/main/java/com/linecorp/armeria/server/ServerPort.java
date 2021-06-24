/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static com.linecorp.armeria.common.SessionProtocol.PROXY;
import static com.linecorp.armeria.common.SessionProtocol.httpValues;
import static com.linecorp.armeria.common.SessionProtocol.httpsValues;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.SessionProtocol;

/**
 * A pair of server-side bind address and {@link SessionProtocol}.
 */
public final class ServerPort implements Comparable<ServerPort> {

    private final InetSocketAddress localAddress;
    private final String comparisonStr;
    private final Set<SessionProtocol> protocols;
    private int hashCode;

    @Nullable
    private String strVal;

    /**
     * Creates a new {@link ServerPort} that listens to the specified {@code port} of all available network
     * interfaces using the specified {@link SessionProtocol}s.
     */
    public ServerPort(int port, SessionProtocol... protocols) {
        this(new InetSocketAddress(port), ImmutableSet.copyOf(requireNonNull(protocols, "protocols")));
    }

    /**
     * Creates a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * {@link SessionProtocol}s.
     */
    public ServerPort(InetSocketAddress localAddress, SessionProtocol... protocols) {
        this(localAddress, ImmutableSet.copyOf(requireNonNull(protocols, "protocols")));
    }

    /**
     * Creates a new {@link ServerPort} that listens to the specified {@code port} of all available network
     * interfaces using the specified {@link SessionProtocol}s.
     */
    public ServerPort(int port, Iterable<SessionProtocol> protocols) {
        this(new InetSocketAddress(port), protocols);
    }

    /**
     * Creates a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * {@link SessionProtocol}s.
     */
    public ServerPort(InetSocketAddress localAddress, Iterable<SessionProtocol> protocols) {
        // Try to resolve the localAddress if not resolved yet.
        if (requireNonNull(localAddress, "localAddress").isUnresolved()) {
            try {
                localAddress = new InetSocketAddress(
                        InetAddress.getByName(localAddress.getHostString()),
                        localAddress.getPort());
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("unresolved localAddress: " + localAddress, e);
            }
        }

        requireNonNull(protocols, "protocols");
        this.localAddress = localAddress;
        this.protocols = Sets.immutableEnumSet(protocols);

        checkArgument(!this.protocols.isEmpty(),
                      "protocols: %s (must not be empty)", this.protocols);
        checkArgument(this.protocols.contains(HTTP) || this.protocols.contains(HTTPS),
                      "protocols: %s (must contain HTTP or HTTPS)", this.protocols);
        checkArgument(this.protocols.stream().allMatch(p -> p == HTTP || p == HTTPS || p == PROXY),
                      "protocols: %s (must not contain other than %s, %s or %s)",
                      this.protocols, HTTP, HTTPS, PROXY);

        comparisonStr = localAddress.getAddress().getHostAddress() + '/' +
                        localAddress.getPort() + '/' + protocols;
    }

    /**
     * Returns the local address this {@link ServerPort} listens to.
     */
    public InetSocketAddress localAddress() {
        return localAddress;
    }

    /**
     * Returns the {@link SessionProtocol}s this {@link ServerPort} uses.
     */
    public Set<SessionProtocol> protocols() {
        return protocols;
    }

    /**
     * Returns whether there is a {@link SessionProtocol} which is over TLS.
     */
    public boolean hasTls() {
        return protocols.stream().anyMatch(SessionProtocol::isTls);
    }

    /**
     * Returns whether the {@link SessionProtocol#HTTP}, {@link SessionProtocol#H1C} or
     * {@link SessionProtocol#H2C} is in the list of {@link SessionProtocol}s.
     */
    public boolean hasHttp() {
        return httpValues().stream().anyMatch(this::hasExactProtocol);
    }

    /**
     * Returns whether the {@link SessionProtocol#HTTPS}, {@link SessionProtocol#H1} or
     * {@link SessionProtocol#H2} is in the list of {@link SessionProtocol}s.
     */
    public boolean hasHttps() {
        return httpsValues().stream().anyMatch(this::hasExactProtocol);
    }

    /**
     * Returns whether the {@link SessionProtocol#PROXY} is in the list of {@link SessionProtocol}s.
     */
    public boolean hasProxyProtocol() {
        return hasExactProtocol(PROXY);
    }

    /**
     * Returns whether the specified {@code protocol} is in the list of {@link SessionProtocol}s.
     */
    public boolean hasProtocol(SessionProtocol protocol) {
        requireNonNull(protocol, "protocol");

        if (httpValues().contains(protocol)) {
            return hasHttp();
        }

        if (httpsValues().contains(protocol)) {
            return hasHttps();
        }

        return hasExactProtocol(protocol);
    }

    private boolean hasExactProtocol(SessionProtocol protocol) {
        return protocols.contains(requireNonNull(protocol, "protocol"));
    }

    @Override
    public int hashCode() {
        int hashCode = this.hashCode;
        if (hashCode == 0) {
            hashCode = comparisonStr.hashCode();
            if (hashCode == 0) {
                hashCode = 1;
            }

            this.hashCode = hashCode;
        }

        return hashCode;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == this) {
            return true;
        }

        if (!(obj instanceof ServerPort)) {
            return false;
        }

        final ServerPort that = (ServerPort) obj;
        final int hashCode = this.hashCode;
        if (hashCode != 0 && that.hashCode != 0 && hashCode != that.hashCode) {
            return false;
        }

        return comparisonStr.equals(that.comparisonStr);
    }

    @Override
    public int compareTo(ServerPort o) {
        return comparisonStr.compareTo(o.comparisonStr);
    }

    @Override
    public String toString() {
        String strVal = this.strVal;
        if (strVal == null) {
            this.strVal = strVal = toString(getClass(), localAddress(), protocols());
        }

        return strVal;
    }

    static String toString(@Nullable Class<?> type, InetSocketAddress localAddress,
                           Set<SessionProtocol> protocols) {
        final StringBuilder buf = new StringBuilder();
        if (type != null) {
            buf.append(type.getSimpleName());
        }
        buf.append('(');
        buf.append(localAddress);
        buf.append(", ");
        buf.append(protocols);
        buf.append(')');

        return buf.toString();
    }
}
