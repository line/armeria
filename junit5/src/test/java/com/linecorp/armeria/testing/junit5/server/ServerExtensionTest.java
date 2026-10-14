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

package com.linecorp.armeria.testing.junit5.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServerPortBindException;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

class ServerExtensionTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void requestContextCaptor() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        client.get("/hello").aggregate().join();

        final ServiceRequestContextCaptor captor = server.requestContextCaptor();
        assertThat(captor.size()).isEqualTo(1);

        assertThat(captor.take().request().uri().getPath()).isEqualTo("/hello");
    }

    @Test
    void startupFailureIsNotRetriedByDefault() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        try (ServerSocket occupiedPort = new ServerSocket(0)) {
            final ServerExtension extension = new ServerExtension(false) {
                @Override
                protected void configure(ServerBuilder sb) {
                    attempts.incrementAndGet();
                    sb.http(occupiedPort.getLocalPort())
                      .service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                }
            };
            assertThatThrownBy(extension::start).isInstanceOf(CompletionException.class)
                                               .hasCauseInstanceOf(ServerPortBindException.class);
            assertThat(attempts.get()).isOne();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 5 })
    void retriesStartupFailuresUntilSuccessful(int successfulAttempt) throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        try (ServerSocket occupiedPort = new ServerSocket(0)) {
            final ServerExtension extension = new ServerExtension() {
                @Override
                protected int numAttemptsOnStartupFailure() {
                    return 5;
                }

                @Override
                protected void configure(ServerBuilder sb) {
                    final int port = attempts.incrementAndGet() < successfulAttempt ?
                                     occupiedPort.getLocalPort() : 0;
                    sb.http(port).service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                }
            };
            try {
                extension.beforeAll(null);
                assertThat(attempts.get()).isEqualTo(successfulAttempt);
                assertThat(extension.start()).isSameAs(extension.server());
                assertThat(attempts.get()).isEqualTo(successfulAttempt);
                assertThat(extension.webClient().get("/hello").aggregate().join().status())
                        .isEqualTo(HttpStatus.OK);
            } finally {
                extension.stop().join();
            }
        }
    }

    @Test
    void exhaustedStartupAttemptsReleaseBoundPorts() throws Exception {
        final List<ServerPort> ports = new ArrayList<>();
        try (ServerSocket occupiedPort = new ServerSocket(0)) {
            final ServerExtension extension = new ServerExtension(false) {
                @Override
                protected int numAttemptsOnStartupFailure() {
                    return 5;
                }

                @Override
                protected void configure(ServerBuilder sb) {
                    final ServerPort port = new ServerPort(0, SessionProtocol.HTTP);
                    ports.add(port);
                    sb.port(port).http(occupiedPort.getLocalPort())
                      .service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                }
            };
            assertThatThrownBy(extension::start).isInstanceOf(CompletionException.class)
                                               .hasCauseInstanceOf(ServerPortBindException.class);
            assertThat(ports).hasSize(5);
            assertThatThrownBy(extension::server).isInstanceOf(IllegalStateException.class);
            for (ServerPort port : ports) {
                assertThat(port.actualPort()).isPositive();
                try (ServerSocket releasedPort = new ServerSocket(port.actualPort())) {
                    assertThat(releasedPort.getLocalPort()).isEqualTo(port.actualPort());
                }
            }
        }
    }

    @Test
    void configurationFailuresAreNotRetried() {
        final AtomicInteger attempts = new AtomicInteger();
        final ServerExtension extension = new ServerExtension(false) {
            @Override
            protected int numAttemptsOnStartupFailure() {
                return 5;
            }

            @Override
            protected void configure(ServerBuilder sb) {
                attempts.incrementAndGet();
                throw new IllegalArgumentException("invalid configuration");
            }
        };
        assertThatThrownBy(extension::start).isInstanceOf(IllegalStateException.class)
                                           .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(attempts.get()).isOne();
    }

    @Test
    void buildFailuresAreNotRetried() {
        final AtomicInteger attempts = new AtomicInteger();
        final ServerExtension extension = new ServerExtension(false) {
            @Override
            protected int numAttemptsOnStartupFailure() {
                return 5;
            }

            @Override
            protected void configure(ServerBuilder sb) {
                attempts.incrementAndGet();
                sb.https(0).service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            }
        };
        assertThatThrownBy(extension::start).isInstanceOf(IllegalArgumentException.class)
                                           .hasMessageContaining("TLS not configured");
        assertThat(attempts.get()).isOne();
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1 })
    void invalidStartupAttemptsAreRejected(int numAttempts) {
        final ServerExtension extension = new ServerExtension(false) {
            @Override
            protected int numAttemptsOnStartupFailure() {
                return numAttempts;
            }

            @Override
            protected void configure(ServerBuilder sb) {}
        };
        assertThatThrownBy(extension::start).isInstanceOf(IllegalArgumentException.class)
                                           .hasMessageContaining("numAttemptsOnStartupFailure");
    }
}
