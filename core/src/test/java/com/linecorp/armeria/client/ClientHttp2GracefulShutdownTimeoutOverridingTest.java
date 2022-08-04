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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.testing.FlakyTest;
import com.linecorp.armeria.server.Server;

// Use FlakyTest because it takes too long to run in :core:test.
@FlakyTest
class ClientHttp2GracefulShutdownTimeoutOverridingTest {

    @Test
    void idleTimeoutIsUsedForHttp2GracefulShutdownTimeout() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final Server server =
                Server.builder()
                      .service("/foo", (ctx, req) -> {
                          return HttpResponse.from(req.aggregate().thenApply(unused -> {
                              latch.countDown();
                              // Return after 40 seconds that is bigger than the default
                              // HTTP/2 timeout 30 seconds.
                              // Http2CodecUtil.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS
                              return HttpResponse.delayed(HttpResponse.of("OK"), Duration.ofSeconds(40));
                          }));
                      })
                      .gracefulShutdownTimeout(Duration.ZERO, Duration.ofDays(1))
                      .requestTimeout(Duration.ofSeconds(60))
                      .build();
        server.start().join();

        try (final ClientFactory factory = ClientFactory.builder()
                                                        .idleTimeout(Duration.ofSeconds(50))
                                                        .build()) {
            final WebClient client = WebClient.builder("http://127.0.0.1:" + server.activeLocalPort())
                                              .factory(factory)
                                              .responseTimeout(Duration.ofSeconds(60))
                                              .build();
            final HttpResponse response = client.get("/foo");
            latch.await();
            // Stop the server to send GOAWAY frames.
            final CompletableFuture<Void> stopFuture = server.stop();
            assertThat(response.aggregate().join().contentUtf8()).isEqualTo("OK");
            stopFuture.join();
        }
    }
}
