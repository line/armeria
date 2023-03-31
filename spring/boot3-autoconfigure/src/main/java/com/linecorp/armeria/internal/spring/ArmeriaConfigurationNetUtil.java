/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.spring;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.PortUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

import io.netty.util.NetUtil;

/**
 * A utility class which is used to configure a {@link ServerBuilder} about networks.
 */
public final class ArmeriaConfigurationNetUtil {

    /**
     * Adds {@link Port}s to the specified {@link ServerBuilder}.
     */
    public static void configurePorts(ServerBuilder server, List<Port> ports) {
        requireNonNull(server, "server");
        requireNonNull(ports, "ports");
        ports.forEach(p -> {
            final String iface = p.getIface();
            final int port = p.getPort();
            final List<SessionProtocol> protocols = firstNonNull(p.getProtocols(),
                                                                 ImmutableList.of(SessionProtocol.HTTP));
            final InetSocketAddress socketAddress = createSocketAddress(p);
            if (socketAddress == null) {
                if (iface == null) {
                    server.port(new ServerPort(port, protocols));
                } else {
                    try {
                        final Enumeration<InetAddress> e = NetworkInterface.getByName(iface).getInetAddresses();
                        while (e.hasMoreElements()) {
                            server.port(new ServerPort(new InetSocketAddress(e.nextElement(), port),
                                                       protocols));
                        }
                    } catch (SocketException e) {
                        throw new IllegalStateException("Failed to find an iface: " + iface, e);
                    }
                }
            } else if (iface == null) {
                server.port(new ServerPort(socketAddress, protocols));
            } else {
                throw new IllegalStateException("A port cannot have both IP and iface: " + p);
            }
        });
    }

    @Nullable
    private static InetSocketAddress createSocketAddress(Port p) {
        final InetAddress address = p.getAddress();
        final String ip = p.getIp();
        final int port = p.getPort();
        if (ip != null && address != null) {
            throw new IllegalStateException("A port cannot have both IP and address: " + p);
        }
        final InetSocketAddress targetAddress;
        if (ip != null) {
            if (NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip)) {
                final byte[] bytes = NetUtil.createByteArrayFromIpAddressString(ip);
                try {
                    targetAddress = new InetSocketAddress(InetAddress.getByAddress(bytes), port);
                } catch (UnknownHostException e) {
                    // Should never happen.
                    throw new Error(e);
                }
            } else {
                throw new IllegalStateException("invalid IP address: " + ip);
            }
        } else if (address != null) {
            targetAddress = new InetSocketAddress(address, port);
        } else {
            targetAddress = null;
        }
        return targetAddress;
    }

    /**
     * Returns a newly created {@link Port}.
     * {@code null} if the specified {@code code} is either {@code null} or a negative number.
     * Note that if the given {@code portNumber} is zero, an available portNumber randomly selected will be
     * assigned.
     */
    @Nullable
    public static Port maybeNewPort(@Nullable Integer portNumber, @Nullable InetAddress serverAddress,
                                    boolean enableSsl) {
        if (portNumber == null || portNumber < 0) {
            return null;
        }
        if (portNumber == 0) {
            portNumber = PortUtil.unusedTcpPort();
        }

        final Port port = new Port().setPort(portNumber);
        if (serverAddress != null) {
            port.setAddress(serverAddress);
        }
        return port.setProtocol(enableSsl ? SessionProtocol.HTTPS : SessionProtocol.HTTP);
    }

    private ArmeriaConfigurationNetUtil() {}
}
