/*
 * Copyright 2026 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * Makes sure that a connection which was negotiating TLS when the server started handing over the
 * connections is handed over as well, so that the requests sent on it are not aborted when the other
 * connections are closed.
 */
class GracefulShutdownTlsHandshakeTest {

    private static final Duration SLOW_RESPONSE_DELAY = Duration.ofMillis(2500);

    @Test
    void connectionNegotiatingTlsShouldBeHandedOver() throws Exception {
        final Server server =
                Server.builder()
                      .https(0)
                      .tlsSelfSigned()
                      // Keep the other connection open long enough for the request on the connection
                      // negotiating TLS to be sent.
                      .connectionDrainDuration(Duration.ofSeconds(2))
                      .service("/fast", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                      .service("/slow", (ctx, req) -> HttpResponse.delayed(HttpResponse.of(HttpStatus.OK),
                                                                           SLOW_RESPONSE_DELAY))
                      .gracefulShutdown(GracefulShutdown.builder()
                                                        .quietPeriod(Duration.ofMillis(200))
                                                        .timeout(Duration.ofSeconds(30))
                                                        .build())
                      .build();
        server.start().join();

        try (HandshakeHoldingProxy proxy = new HandshakeHoldingProxy(server.activeLocalPort());
             ClientFactory factory = ClientFactory.builder()
                                                  .tlsNoVerify()
                                                  .http2GracefulShutdownTimeout(Duration.ofSeconds(10))
                                                  .build()) {
            final WebClient directClient =
                    WebClient.builder(SessionProtocol.H2, Endpoint.of("127.0.0.1", server.activeLocalPort()))
                             .factory(factory)
                             .responseTimeoutMillis(0)
                             .build();
            final WebClient proxiedClient =
                    WebClient.builder(SessionProtocol.H2, Endpoint.of("127.0.0.1", proxy.port()))
                             .factory(factory)
                             .responseTimeoutMillis(0)
                             .build();

            // Establish a connection which is handed over when the quiet period completes.
            assertThat(directClient.get("/fast").aggregate().join().status()).isEqualTo(HttpStatus.OK);

            // Establish a connection whose TLS handshake does not complete until the proxy is released.
            final CompletableFuture<AggregatedHttpResponse> slowRes = proxiedClient.get("/slow").aggregate();
            await().until(() -> server.numConnections() == 2);

            final CompletableFuture<Void> stopFuture = server.stop();
            // The handover starts right after the server ports are closed.
            await().pollInterval(Duration.ofMillis(10))
                   .until(() -> server.activePorts().isEmpty());
            Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(300));
            proxy.release();

            // The request is either served or rejected before being processed, so that it can be retried.
            final Throwable cause = slowRes.handle((res, thrown) -> {
                if (thrown == null) {
                    assertThat(res.status()).isEqualTo(HttpStatus.OK);
                }
                return thrown;
            }).join();
            if (cause != null) {
                assertThat(Exceptions.peel(cause)).isInstanceOf(UnprocessedRequestException.class);
            }

            stopFuture.join();
        } finally {
            server.stop().join();
        }
    }

    /**
     * Forwards the bytes from the clients to the server only after {@link #release()} is called.
     */
    private static final class HandshakeHoldingProxy implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final int targetPort;
        private final CountDownLatch released = new CountDownLatch(1);
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        HandshakeHoldingProxy(int targetPort) throws IOException {
            this.targetPort = targetPort;
            serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            executor.execute(this::accept);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void release() {
            released.countDown();
        }

        private void accept() {
            while (!serverSocket.isClosed()) {
                final Socket client;
                final Socket target;
                try {
                    client = serverSocket.accept();
                    sockets.add(client);
                } catch (IOException e) {
                    return;
                }
                try {
                    target = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                    sockets.add(target);
                } catch (IOException e) {
                    closeQuietly(client);
                    continue;
                }
                executor.execute(() -> {
                    Uninterruptibles.awaitUninterruptibly(released);
                    forward(client, target);
                });
                executor.execute(() -> forward(target, client));
            }
        }

        private static void forward(Socket from, Socket to) {
            final byte[] buf = new byte[8192];
            try {
                final InputStream in = from.getInputStream();
                final OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // Closed.
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Ignore.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            sockets.forEach(HandshakeHoldingProxy::closeQuietly);
            // Unblock the forwarding tasks if the test failed before releasing the proxy.
            release();
            executor.shutdownNow();
        }
    }
}
