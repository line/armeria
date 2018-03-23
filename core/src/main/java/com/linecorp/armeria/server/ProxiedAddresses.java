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

import java.net.SocketAddress;

import com.google.common.base.MoreObjects;

/**
 * An interface to provide source and destination addresses delivered from a proxy server.
 */
public final class ProxiedAddresses {

    /**
     * Creates a new instance.
     */
    public static ProxiedAddresses of(SocketAddress sourceAddress, SocketAddress destinationAddress) {
        return new ProxiedAddresses(sourceAddress, destinationAddress);
    }

    private final SocketAddress sourceAddress;
    private final SocketAddress destinationAddress;

    private ProxiedAddresses(SocketAddress sourceAddress, SocketAddress destinationAddress) {
        this.sourceAddress = requireNonNull(sourceAddress, "sourceAddress");
        this.destinationAddress = requireNonNull(destinationAddress, "destinationAddress");
    }

    /**
     * Returns the source address of the proxied request.
     */
    @SuppressWarnings("unchecked")
    public <A extends SocketAddress> A sourceAddress() {
        return (A) sourceAddress;
    }

    /**
     * Returns the destination address of the proxied request.
     */
    @SuppressWarnings("unchecked")
    public <A extends SocketAddress> A destinationAddress() {
        return (A) destinationAddress;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("sourceAddress", sourceAddress)
                          .add("destinationAddress", destinationAddress)
                          .toString();
    }
}
