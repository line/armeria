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
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import com.linecorp.armeria.common.SessionProtocol;

/**
 * A pair of server-side bind address and {@link SessionProtocol}.
 */
public final class ServerPort implements Comparable<ServerPort> {

    private final InetSocketAddress localAddress;
    private final String localAddressString;
    private final SessionProtocol protocol;
    private int hashCode;
    private String strVal;

    /**
     * Creates a new {@link ServerPort} that listens to the specified {@code port} of all available network
     * interfaces using the specified {@link SessionProtocol}.
     */
    public ServerPort(int port, SessionProtocol protocol) {
        this(new InetSocketAddress(port), protocol);
    }

    /**
     * Creates a new {@link ServerPort} that listens to the specified {@code localAddress} using the specified
     * {@link SessionProtocol}.
     */
    public ServerPort(InetSocketAddress localAddress, SessionProtocol protocol) {

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

        requireNonNull(protocol, "protocol");
        checkArgument(protocol == HTTP || protocol == HTTPS,
                      "protocol: %s (expected: %s or %s)", protocol, HTTP, HTTPS);

        this.localAddress = localAddress;
        this.protocol = protocol;

        localAddressString = localAddress.getAddress().getHostAddress() + ':' + localAddress.getPort();
    }

    /**
     * Returns the local address this {@link ServerPort} listens to.
     */
    public InetSocketAddress localAddress() {
        return localAddress;
    }

    /**
     * Returns the {@link SessionProtocol} this {@link ServerPort} uses.
     */
    public SessionProtocol protocol() {
        return protocol;
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
            this.strVal = strVal = toString(getClass(), localAddress(), protocol());
        }

        return strVal;
    }

    static String toString(Class<?> type, InetSocketAddress localAddress, SessionProtocol protocol) {
        StringBuilder buf = new StringBuilder();
        if (type != null) {
            buf.append(type.getSimpleName());
        }
        buf.append('(');
        buf.append(localAddress);
        buf.append(", ");
        buf.append(protocol);
        buf.append(')');

        return buf.toString();
    }
}
