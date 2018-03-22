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

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * An interface to provide source and destination addresses delivered from a proxy server.
 */
public interface ProxiedAddresses {

    /**
     * Creates an {@link InetProxiedAddress} instance.
     */
    static InetProxiedAddress of(String sourceAddress, int sourcePort,
                                 String destinationAddress, int destinationPort) {
        return new InetProxiedAddress(
                InetSocketAddress.createUnresolved(sourceAddress, sourcePort),
                InetSocketAddress.createUnresolved(destinationAddress, destinationPort));
    }

    /**
     * Creates an {@link InetProxiedAddress} instance.
     */
    static InetProxiedAddress of(InetSocketAddress sourceAddress,
                                 InetSocketAddress destinationAddress) {
        return new InetProxiedAddress(sourceAddress, destinationAddress);
    }

    /**
     * Returns the source address of the proxied request.
     */
    <A extends SocketAddress> A sourceAddress();

    /**
     * Returns the destination address of the proxied request.
     */
    <A extends SocketAddress> A destinationAddress();
}
