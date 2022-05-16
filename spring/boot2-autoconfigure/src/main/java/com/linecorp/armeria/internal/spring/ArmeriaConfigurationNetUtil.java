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

import org.springframework.util.SocketUtils;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
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
            final String ip = p.getIp();
            final String iface = p.getIface();
            final int port = p.getPort();
            final List<SessionProtocol> protocols = firstNonNull(p.getProtocols(),
                                                                 ImmutableList.of(SessionProtocol.HTTP));

            if (ip == null) {
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
                if (NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip)) {
                    final byte[] bytes = NetUtil.createByteArrayFromIpAddressString(ip);
                    try {
                        server.port(new ServerPort(new InetSocketAddress(
                                InetAddress.getByAddress(bytes), port), protocols));
                    } catch (UnknownHostException e) {
                        // Should never happen.
                        throw new Error(e);
                    }
                } else {
                    throw new IllegalStateException("invalid IP address: " + ip);
                }
            } else {
                throw new IllegalStateException("A port cannot have both IP and iface: " + p);
            }
        });
    }

    /**
     * Returns a newly created {@link Port}.
     * {@code null} if the specified {@code code} is either {@code null} or a negative number.
     * Note that if the given {@code port} is zero, an available port randomly selected will be assigned.
     */
    @Nullable
    public static Port maybeNewPort(@Nullable Integer port, SessionProtocol protocol) {
        if (port == null || port < 0) {
            return null;
        }
        if (port == 0) {
            port = SocketUtils.findAvailableTcpPort();
        }
        return new Port().setPort(port).setProtocol(protocol);
    }

    private ArmeriaConfigurationNetUtil() {}
}
