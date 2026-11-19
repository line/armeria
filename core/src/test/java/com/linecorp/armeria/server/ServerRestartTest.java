/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;

import io.netty.util.NetUtil;

class ServerRestartTest {

    @Test
    void restartsAnEphemeralPortWithItsVirtualHost() throws IOException {
        final ServerPort configuredPort = new ServerPort(new InetSocketAddress(NetUtil.LOCALHOST4, 0), HTTP);
        try (Server server = Server.builder()
                                   .port(configuredPort)
                                   .virtualHost(configuredPort)
                                   .defaultHostname("restart.example")
                                   .service("/", (ctx, req) -> HttpResponse.of("port-based virtual host"))
                                   .and()
                                   .service("/", (ctx, req) -> HttpResponse.of("default virtual host"))
                                   .build()) {
            server.start().join();
            final int previousPort = server.activeLocalPort();
            final VirtualHost virtualHost = server.config().findVirtualHost("restart.example", previousPort);
            assertThat(virtualHost).extracting(VirtualHost::hostnamePattern, VirtualHost::defaultHostname)
                                   .containsExactly("*:" + previousPort, "restart.example:" + previousPort);
            assertThat(get(previousPort)).isEqualTo("port-based virtual host");

            server.stop().join();
            assertThat(server.activePorts()).isEmpty();

            // Keep the old port occupied so the next bind must allocate a different one.
            try (ServerSocket previousBinding = new ServerSocket()) {
                previousBinding.setReuseAddress(true);
                previousBinding.bind(new InetSocketAddress(NetUtil.LOCALHOST4, previousPort));

                server.start().join();
                final int currentPort = server.activeLocalPort();
                assertThat(currentPort).isPositive().isNotEqualTo(previousPort);
                assertThat(configuredPort.actualPort()).isEqualTo(currentPort);
                assertThat(virtualHost).extracting(VirtualHost::hostnamePattern, VirtualHost::defaultHostname)
                                       .containsExactly("*:" + currentPort, "restart.example:" + currentPort);
                assertThat(get(currentPort)).isEqualTo("port-based virtual host");
            }
        }
    }

    @Test
    void retriesAfterBindingOnlyTheFirstPort() throws IOException {
        final ServerPort ephemeral = new ServerPort(new InetSocketAddress(NetUtil.LOCALHOST4, 0), HTTP);
        final ServerSocket occupied = new ServerSocket(0, 0, NetUtil.LOCALHOST4);
        final InetSocketAddress fixedAddress = (InetSocketAddress) occupied.getLocalSocketAddress();
        try (Server server = Server.builder()
                                   .port(ephemeral)
                                   .http(fixedAddress)
                                   .service("/", (ctx, req) -> HttpResponse.of("ok"))
                                   .build()) {
            assertThatThrownBy(() -> server.start().join()).cause()
                    .isInstanceOfSatisfying(ServerPortBindException.class, cause -> {
                        assertThat(cause.serverPort().localAddress()).isEqualTo(fixedAddress);
                    });
            assertThat(server.activePorts()).isEmpty();
            occupied.close();

            server.start().join();
            final InetSocketAddress ephemeralAddress =
                    new InetSocketAddress(NetUtil.LOCALHOST4, ephemeral.actualPort());
            assertThat(server.activePorts()).containsOnlyKeys(fixedAddress, ephemeralAddress);
            assertThat(get(ephemeralAddress.getPort())).isEqualTo("ok");
            assertThat(get(fixedAddress.getPort())).isEqualTo("ok");
        } finally {
            occupied.close();
        }
    }

    private static String get(int port) {
        return WebClient.of("http://127.0.0.1:" + port).get("/").aggregate().join().contentUtf8();
    }
}
