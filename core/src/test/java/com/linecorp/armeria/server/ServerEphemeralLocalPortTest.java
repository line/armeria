/*
 * Copyright 2021 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.Collection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.NetUtil;

@DisabledIf("io.netty.util.NetUtil#isIpV4StackPreferred")
class ServerEphemeralLocalPortTest {

    @RegisterExtension
    static final ServerExtension serverBuiltWithLocalPort = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.localPort(0, SessionProtocol.HTTP);
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @RegisterExtension
    static final ServerExtension serverBuiltWithoutLocalPort = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(new InetSocketAddress(NetUtil.LOCALHOST4, 0));
            sb.http(new InetSocketAddress(NetUtil.LOCALHOST6, 0));
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    /**
     * {@link Server} must use the same port number for all loopback addresses when 1) the port number is 0 and
     * 2) the port number was specified with {@link ServerBuilder#localPort(int, Iterable)}.
     */
    @Test
    void builtWithLocalPort() {
        final Collection<ServerPort> ports = serverBuiltWithLocalPort.server().activePorts().values();

        // Must bound to both IPv4 and IPv6 loopback addresses.
        assertThat(ports).hasSize(2);

        // Must be loopback addresses and be marked as 'ephemeral local' internally.
        ports.forEach(p -> {
            assertThat(p.localAddress().getAddress().isLoopbackAddress()).isTrue();
            assertThat(p.isEphemeralLocalPort()).isTrue();
        });

        // Must bound at the same port number.
        assertThat(ports.stream().mapToInt(p -> p.localAddress().getPort()).distinct()).hasSize(1);
    }

    /**
     * {@link Server} must not use the same port number for the loopback addresses if a user did not use
     * {@link ServerBuilder#localPort(int, SessionProtocol...)}.
     */
    @Test
    void builtWithoutLocalPort() {
        final Collection<ServerPort> ports = serverBuiltWithoutLocalPort.server().activePorts().values();

        // Must bound to both IPv4 and IPv6 loopback addresses.
        assertThat(ports).hasSize(2);

        // Must be loopback addresses but not be marked as 'ephemeral local' internally.
        ports.forEach(p -> {
            assertThat(p.localAddress().getAddress().isLoopbackAddress()).isTrue();
            assertThat(p.isEphemeralLocalPort()).isFalse();
        });

        // Must bound at two different port numbers because we didn't use `localPort()`.
        assertThat(ports.stream().mapToInt(p -> p.localAddress().getPort()).distinct()).hasSize(2);
    }
}
