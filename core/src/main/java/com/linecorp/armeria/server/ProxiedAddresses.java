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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

/**
 * An interface to provide source and destination addresses delivered from a proxy server.
 */
public final class ProxiedAddresses {

    /**
     * Creates a new instance with the specified {@code sourceAddress}.
     * Note that {@linkplain InetSocketAddress#getPort() port} {@code 0} means that the port number is unknown.
     */
    public static ProxiedAddresses of(InetSocketAddress sourceAddress) {
        return new ProxiedAddresses(sourceAddress, ImmutableList.of());
    }

    /**
     * Creates a new instance with the specified {@code sourceAddress} and {@code destinationAddress}.
     * Note that {@linkplain InetSocketAddress#getPort() port} {@code 0} means that the port number is unknown.
     */
    public static ProxiedAddresses of(InetSocketAddress sourceAddress, InetSocketAddress destinationAddress) {
        requireNonNull(destinationAddress, "destinationAddress");
        return new ProxiedAddresses(sourceAddress, ImmutableList.of(destinationAddress));
    }

    /**
     * Creates a new instance with the specified {@code sourceAddress} and {@code destinationAddresses}.
     * Note that {@linkplain InetSocketAddress#getPort() port} {@code 0} means that the port number is unknown.
     */
    public static ProxiedAddresses of(InetSocketAddress sourceAddress,
                                      Iterable<? extends InetSocketAddress> destinationAddresses) {
        requireNonNull(destinationAddresses, "destinationAddresses");
        return new ProxiedAddresses(sourceAddress, ImmutableList.copyOf(destinationAddresses));
    }

    private final InetSocketAddress sourceAddress;
    private final List<InetSocketAddress> destinationAddresses;

    private ProxiedAddresses(InetSocketAddress sourceAddress, List<InetSocketAddress> destinationAddresses) {
        this.sourceAddress = requireNonNull(sourceAddress, "sourceAddress");
        this.destinationAddresses = requireNonNull(destinationAddresses, "destinationAddresses");
    }

    /**
     * Returns the source address of the proxied request.
     * Note that {@linkplain InetSocketAddress#getPort() port} {@code 0} means that the port number is unknown.
     */
    public InetSocketAddress sourceAddress() {
        return sourceAddress;
    }

    /**
     * Returns the destination addresses of the proxied request.
     * Note that {@linkplain InetSocketAddress#getPort() port} {@code 0} means that the port number is unknown.
     */
    public List<InetSocketAddress> destinationAddresses() {
        return destinationAddresses;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ProxiedAddresses)) {
            return false;
        }

        final ProxiedAddresses that = (ProxiedAddresses) o;
        return sourceAddress.equals(that.sourceAddress) &&
               destinationAddresses.equals(that.destinationAddresses);
    }

    @Override
    public int hashCode() {
        return sourceAddress.hashCode() * 31 + destinationAddresses.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("sourceAddress", sourceAddress)
                          .add("destinationAddress", destinationAddresses)
                          .toString();
    }
}
