/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.it.server;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.BINARY;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.SleepService;
import com.linecorp.armeria.service.test.thrift.main.SleepService.AsyncIface;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class GracefulShutdownIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(GracefulShutdownIntegrationTest.class);

    private static final AtomicInteger accessLogWriterCounter1 = new AtomicInteger();
    private static final AtomicInteger accessLogWriterCounter2 = new AtomicInteger();

    private static final AtomicBoolean sleepServiceCalled = new AtomicBoolean();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.gracefulShutdownTimeoutMillis(1000L, 2000L);
            sb.requestTimeoutMillis(0); // Disable RequestTimeoutException.

            sb.service("/sleep", THttpService.of(
                    (AsyncIface) (milliseconds, resultHandler) -> {
                        sleepServiceCalled.set(true);
                        ServiceRequestContext.current().eventLoop().schedule(
                                () -> resultHandler.onComplete(milliseconds), milliseconds, MILLISECONDS);
                    }));

            final AccessLogWriter writer1 = new AccessLogWriter() {
                @Override
                public void log(RequestLog log) {}

                @Override
                public CompletableFuture<Void> shutdown() {
                    accessLogWriterCounter1.getAndIncrement();
                    return CompletableFuture.completedFuture(null);
                }
            };
            final AccessLogWriter writer2 = new AccessLogWriter() {
                @Override
                public void log(RequestLog log) {}

                @Override
                public CompletableFuture<Void> shutdown() {
                    accessLogWriterCounter2.getAndIncrement();
                    return CompletableFuture.completedFuture(null);
                }
            };
            sb.route()
              .get("/dummy1")
              .accessLogWriter(writer1, true)
              .build((ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.route()
              .get("/dummy2")
              .accessLogWriter(writer1, true)
              .build((ctx, req) -> HttpResponse.of(HttpStatus.OK));

            sb.route()
              .get("/dummy3")
              .accessLogWriter(writer2, true)
              .build((ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    private ClientFactory clientFactory;

    @BeforeEach
    void setUp() {
        sleepServiceCalled.set(false);
        clientFactory = ClientFactory.builder()
                                     .idleTimeoutMillis(0)
                                     .build();
    }

    @AfterEach
    void tearDown() {
        clientFactory.closeAsync();
    }

    private static long baselineNanos;

    private static long baselineNanos() throws Exception {
        if (baselineNanos != 0) {
            return baselineNanos;
        }

        // Measure the baseline time taken for stopping the server without handling any requests.
        server.start();
        final long startTime = System.nanoTime();
        server.stop().join();
        final long stopTime = System.nanoTime();

        assertThat(accessLogWriterCounter1.get()).isOne();
        assertThat(accessLogWriterCounter2.get()).isOne();
        return baselineNanos = stopTime - startTime;
    }

    @Test
    void testBaseline() throws Exception {
        final long baselineNanos = baselineNanos();

        // Measure the time taken for stopping the server after handling a single request.
        server.start();
        final SleepService.Iface client = newClient();
        client.sleep(0);
        final long startTime = System.nanoTime();
        server.stop().join();
        final long stopTime = System.nanoTime();

        // .. which should be on par with the baseline.
        assertThat(stopTime - startTime).isBetween(baselineNanos - MILLISECONDS.toNanos(400),
                                                   baselineNanos + MILLISECONDS.toNanos(400));
    }

    @Test
    void waitsForRequestToComplete() throws Exception {
        final long baselineNanos = baselineNanos();
        server.start();

        final SleepService.Iface client = newClient();
        final AtomicBoolean completed = new AtomicBoolean(false);
        CompletableFuture.runAsync(() -> {
            try {
                client.sleep(500L);
                completed.set(true);
            } catch (Throwable t) {
                logger.error("Unexpected failure:", t);
            }
        });

        // Wait for making sure the request has been sent before shutting down.
        await().untilTrue(sleepServiceCalled);
        final long startTime = System.nanoTime();
        server.stop().join();
        final long stopTime = System.nanoTime();
        assertThat(completed.get()).isTrue();

        // Should take 500 more milliseconds than the baseline.
        assertThat(stopTime - startTime).isBetween(baselineNanos + MILLISECONDS.toNanos(100),
                                                   baselineNanos + MILLISECONDS.toNanos(900));
    }

    @Test
    void interruptsSlowRequests() throws Exception {
        final long baselineNanos = baselineNanos();
        server.start();

        final SleepService.Iface client = newClient();
        final AtomicBoolean completed = new AtomicBoolean(false);
        final CountDownLatch latch1 = new CountDownLatch(1);
        final CountDownLatch latch2 = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> {
            try {
                latch1.countDown();
                client.sleep(30000L);
                completed.set(true);
            } catch (ClosedSessionException expected) {
                latch2.countDown();
            } catch (Throwable t) {
                logger.error("Unexpected failure:", t);
            }
        });

        // Wait for the latch to make sure the request has been sent before shutting down.
        latch1.await();

        final long startTime = System.nanoTime();
        server.stop().join();
        assertThat(completed.get()).isFalse();

        // 'client.sleep()' must fail immediately when the server closes the connection.
        latch2.await();

        // Should take 1 more second than the baseline, because the long sleep will trigger shutdown timeout.
        final long stopTime = System.nanoTime();
        assertThat(stopTime - startTime).isBetween(baselineNanos + MILLISECONDS.toNanos(600),
                                                   baselineNanos + MILLISECONDS.toNanos(1400));
    }

    @Test
    void testHardTimeout() throws Exception {
        final long baselineNanos = baselineNanos();
        final Server server = GracefulShutdownIntegrationTest.server.start();

        final SleepService.Iface client = newClient();
        // Send the first request to warm up the client connection, because otherwise
        // the quiet period may end while the client establishes a connection on a busy machine.
        client.sleep(0);

        final CompletableFuture<Long> stopFuture = CompletableFuture.supplyAsync(() -> {
            logger.debug("Server shutting down");
            final long startTime = System.nanoTime();
            server.stop().join();
            final long stopTime = System.nanoTime();
            logger.debug("Server shut down");
            return stopTime - startTime;
        });

        // Keep sending a request while shutting down so that the hard limit is reached.
        for (int i = 1;; i++) {
            try {
                client.sleep(50);
            } catch (Exception e) {
                // Server has been shut down
                logger.debug("Client detected server shutdown after {} calls:", i, e);
                break;
            }
        }

        // Should take 1 more second than the baseline, because the requests will extend the quiet period
        // until the shutdown timeout is triggered.
        assertThat(stopFuture.join()).isBetween(baselineNanos + MILLISECONDS.toNanos(600),
                                                baselineNanos + MILLISECONDS.toNanos(1400));
    }

    private SleepService.Iface newClient() {
        return Clients.builder(server.httpUri(BINARY) + "/sleep")
                      .factory(clientFactory)
                      .build(SleepService.Iface.class);
    }
}
