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
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.StringJoiner;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.common.SessionProtocol;

/**
 * A pair of server-side bind address and {@link SessionProtocol}.
 */
public final class ServerPort implements Comparable<ServerPort> {

    private final InetSocketAddress localAddress;
    private final String localAddressString;
    private final Set<SessionProtocol> protocols;
    private int hashCode;
    private String protocolNames;

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
    public ServerPort(int port, Set<SessionProtocol> protocols) {
        this(new InetSocketAddress(port), protocols);
    }

    /**
     * Creates a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * {@link SessionProtocol}s.
     */
    public ServerPort(InetSocketAddress localAddress, Set<SessionProtocol> protocols) {
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
        checkArgument(protocols.stream().allMatch(p -> p == HTTP || p == HTTPS || p == PROXY),
                      "protocol: %s (expected: %s, %s or %s)", protocols, HTTP, HTTPS, PROXY);
        this.localAddress = localAddress;
        this.protocols = ImmutableSortedSet.copyOf(protocols);

        final StringJoiner protocolNameJoiner = new StringJoiner("+");
        this.protocols.forEach(p -> protocolNameJoiner.add(p.uriText()));
        protocolNames = protocolNameJoiner.toString();

        localAddressString = localAddress.getAddress().getHostAddress() + ':' +
                             localAddress.getPort() + ':' + protocolNames;
    }

    /**
     * Returns the local address this {@link ServerPort} listens to.
     */
    public InetSocketAddress localAddress() {
        return localAddress;
    }

    /**
     * Returns the {@link SessionProtocol} this {@link ServerPort} uses.
     *
     * @deprecated Use {@link #protocols()} instead.
     */
    @Deprecated
    public SessionProtocol protocol() {
        // TODO(hyangtack) Leave this for backward compatability. Remove it later.
        return protocols.iterator().next();
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
     * Returns whether the {@link SessionProtocol#HTTP} is in the list of {@link SessionProtocol}s.
     */
    public boolean hasHttp() {
        return hasProtocol(HTTP);
    }

    /**
     * Returns whether the {@link SessionProtocol#HTTPS} is in the list of {@link SessionProtocol}s.
     */
    public boolean hasHttps() {
        return hasProtocol(HTTPS);
    }

    /**
     * Returns whether the {@link SessionProtocol#PROXY} is in the list of {@link SessionProtocol}s.
     */
    public boolean hasProxy() {
        return hasProtocol(PROXY);
    }

    /**
     * Returns whether the specified {@code protocol} is in the list of {@link SessionProtocol}s.
     */
    public boolean hasProtocol(SessionProtocol protocol) {
        return protocols.stream().anyMatch(p -> p == protocol);
    }

    /**
     * Returns all protocol names which are concatenated with {@code +}.
     */
    public String protocolNames() {
        return protocolNames;
    }

    @Override
    public int hashCode() {
        int hashCode = this.hashCode;
        if (hashCode == 0) {
            hashCode = localAddressString.hashCode();
            if (hashCode == 0) {
                hashCode = 1;
            }

            this.hashCode = hashCode;
        }

        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        if (!(obj instanceof ServerPort)) {
            return false;
        }

        ServerPort that = (ServerPort) obj;
        int hashCode = this.hashCode;
        if (hashCode != 0 && that.hashCode != 0 && hashCode != that.hashCode) {
            return false;
        }

        return localAddressString.equals(that.localAddressString);
    }

    @Override
    public int compareTo(ServerPort o) {
        return localAddressString.compareTo(o.localAddressString);
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
