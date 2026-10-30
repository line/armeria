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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.EventLoopGroups;

import io.netty.channel.EventLoopGroup;

/**
 * Makes sure that a request received on an existing HTTP/2 connection after the quiet period has completed
 * is not aborted when the connection is closed, as long as the graceful shutdown timeout has not elapsed.
 */
class GracefulShutdownLateHttp2RequestTest {

    private static final Duration SLOW_RESPONSE_DELAY = Duration.ofMillis(2500);

    @Test
    void requestAcceptedAfterQuietPeriodShouldCompleteWithinTimeout() {
        final CompletableFuture<ServiceRequestContext> slowRequestReceived = new CompletableFuture<>();
        // Use a single event loop so that the test can stop the server from sending a GOAWAY frame.
        final EventLoopGroup workerGroup = EventLoopGroups.newEventLoopGroup(1);
        final Server server =
                Server.builder()
                      .http(0)
                      .workerGroup(workerGroup, true)
                      .service("/fast", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                      .service("/slow", (ctx, req) -> {
                          slowRequestReceived.complete(ctx);
                          return HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), SLOW_RESPONSE_DELAY);
                      })
                      .gracefulShutdown(GracefulShutdown.builder()
                                                        .quietPeriod(Duration.ofMillis(200))
                                                        .timeout(Duration.ofSeconds(30))
                                                        .build())
                      .build();
        server.start().join();

        final CountDownLatch unblockEventLoop = new CountDownLatch(1);
        // Wait for the active streams longer than the default 1 second after receiving the last GOAWAY frame,
        // so that the client does not close the connection before the server does.
        try (ClientFactory factory = ClientFactory.builder()
                                                  .http2GracefulShutdownTimeout(Duration.ofSeconds(10))
                                                  .build()) {
            final WebClient client = WebClient.builder(SessionProtocol.H2C,
                                                       Endpoint.of("127.0.0.1", server.activeLocalPort()))
                                              .factory(factory)
                                              .responseTimeoutMillis(0)
                                              .build();

            // Establish an HTTP/2 connection.
            assertThat(client.get("/fast").aggregate().join().status()).isEqualTo(HttpStatus.OK);

            workerGroup.execute(() -> Uninterruptibles.awaitUninterruptibly(unblockEventLoop));
            final CompletableFuture<Void> stopFuture = server.stop();

            // The server ports are closed only after the quiet period has completed.
            await().pollInterval(Duration.ofMillis(10))
                   .until(() -> server.activePorts().isEmpty());

            // Send a request on the existing connection before the server can send a GOAWAY frame.
            final CompletableFuture<AggregatedHttpResponse> slowRes;
            final ClientRequestContext cctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                slowRes = client.get("/slow").aggregate();
                cctx = captor.get();
            }
            cctx.log().whenRequestComplete().join();
            unblockEventLoop.countDown();
            slowRequestReceived.join();

            // The response needs 2.5 seconds, which is well within the 30-second graceful shutdown timeout.
            final AggregatedHttpResponse res = slowRes.handle((r, cause) -> {
                assertThat(cause).as("accepted request should not be failed by shutdown").isNull();
                return r;
            }).join();
            assertThat(res.status()).isEqualTo(HttpStatus.OK);

            stopFuture.join();
        } finally {
            unblockEventLoop.countDown();
            server.stop().join();
        }
    }
}
