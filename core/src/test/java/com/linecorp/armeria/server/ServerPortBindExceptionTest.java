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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collection;

import org.junit.jupiter.api.Test;

import com.google.common.base.Throwables;

import com.linecorp.armeria.common.HttpResponse;

class ServerPortBindExceptionTest {

    /**
     * Binding to a port that is already in use must surface a {@link ServerPortBindException} whose
     * {@link ServerPortBindException#serverPort()} is the conflicting port and whose
     * {@link ServerPortBindException#getCause()} is the original {@link BindException}.
     */
    @Test
    void bindFailureIsWrappedWithFailedPort() throws IOException {
        final InetAddress loopback = InetAddress.getLoopbackAddress();
        // Occupy a port so that the server fails to bind to it.
        try (ServerSocket occupied = new ServerSocket(0, 0, loopback)) {
            final int port = occupied.getLocalPort();
            final Server server = Server.builder()
                                        .http(new InetSocketAddress(loopback, port))
                                        .service("/", (ctx, req) -> HttpResponse.of(200))
                                        .build();

            final Throwable thrown = catchThrowable(() -> server.start().join());
            assertThat(thrown).isNotNull();

            final ServerPortBindException bindException =
                    Throwables.getCausalChain(thrown).stream()
                              .filter(ServerPortBindException.class::isInstance)
                              .map(ServerPortBindException.class::cast)
                              .findFirst()
                              .orElse(null);
            assertThat(bindException).isNotNull();
            assertThat(bindException.serverPort().localAddress().getPort()).isEqualTo(port);
            assertThat(bindException.serverPort().protocols()).containsExactly(HTTP);
            assertThat(bindException).hasMessageContaining("Failed to bind to")
                                     .hasMessageContaining("http");
            // The original bind failure must be preserved as the cause. Depending on the transport, it is
            // either a java.net.BindException (NIO) or a Netty NativeIoException (epoll/kqueue/io_uring),
            // both of which are IOExceptions reporting that the address is already in use.
            assertThat(bindException.getCause())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Address already in use");
        }
    }

    /**
     * The exception must preserve the original cause and the failed port, and expose a port- and
     * protocol-aware message.
     */
    @Test
    void preservesCauseAndPort() {
        final ServerPort port = new ServerPort(8080, HTTP);
        final BindException cause = new BindException("Address already in use");
        final ServerPortBindException bindException = new ServerPortBindException(port, cause);
        assertThat(bindException.serverPort()).isSameAs(port);
        assertThat(bindException.getCause()).isSameAs(cause);
        assertThat(bindException).hasMessageContaining("Failed to bind to")
                                 .hasMessageContaining("http");
    }

    /**
     * The happy path must be unaffected: a server with multiple ephemeral ports starts normally.
     */
    @Test
    void happyPathWithMultipleEphemeralPorts() {
        final Server server = Server.builder()
                                    .http(0)
                                    .http(0)
                                    .https(0)
                                    .tlsSelfSigned()
                                    .service("/", (ctx, req) -> HttpResponse.of(200))
                                    .build();
        try {
            server.start().join();
            final Collection<ServerPort> ports = server.activePorts().values();
            assertThat(ports).hasSize(3);
            assertThat(ports.stream().mapToInt(p -> p.localAddress().getPort()))
                    .allMatch(p -> p > 0);
        } finally {
            server.stop().join();
        }
    }
}
