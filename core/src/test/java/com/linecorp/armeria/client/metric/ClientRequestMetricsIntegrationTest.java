/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.metric;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.CancellationException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeMap;

class ClientRequestMetricsIntegrationTest {

    private static final CountDownLatch SCENARIO1_CLIENT1_ARRIVED = new CountDownLatch(1);
    private static final CountDownLatch SCENARIO1_CLIENT1_RESPONSE = new CountDownLatch(1);
    private static final String SCENARIO1_CLIENT1_PATH = "/scenario1-client1";

    private static final CountDownLatch SCENARIO1_CLIENT2_ARRIVED = new CountDownLatch(1);
    private static final CountDownLatch SCENARIO1_CLIENT2_RESPONSE = new CountDownLatch(1);
    private static final String SCENARIO1_CLIENT2_PATH = "/scenario2-client2";

    private static final CountDownLatch SCENARIO2_NEVER_CALLED = new CountDownLatch(1);
    private static final String SCENARIO2_PATH = "/scenario2";

    private static final String SCENARIO3_PATH = "/scenario3";

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(SCENARIO1_CLIENT1_PATH, (ctx, req) -> HttpResponse.of(
                    () -> {
                        SCENARIO1_CLIENT1_ARRIVED.countDown();
                        try {
                            SCENARIO1_CLIENT1_RESPONSE.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        return HttpResponse.of("ok");
                    },
                    ctx.blockingTaskExecutor()
            ));

            sb.service(SCENARIO1_CLIENT2_PATH, (ctx, req) -> HttpResponse.of(
                    () -> {
                        SCENARIO1_CLIENT2_ARRIVED.countDown();
                        try {
                            SCENARIO1_CLIENT2_RESPONSE.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        return HttpResponse.of("ok");
                    },
                    ctx.blockingTaskExecutor()
            ));

            sb.service(SCENARIO2_PATH, (ctx, req) -> {
                SCENARIO2_NEVER_CALLED.countDown();
                return HttpResponse.of("ok");
            });
        }
    };

    @Test
    void client_request_metric_basic_test() throws InterruptedException {
        final ClientRequestMetrics metrics = ClientRequestLifecycleListener.counting();
        final ClientFactoryBuilder factoryBuilder = ClientFactory
                .builder()
                .clientRequestLifecycleListener(metrics);
        try (ClientFactory clientFactory = factoryBuilder.build()) {
            final HttpResponse res1 = WebClient.builder("http://127.0.0.1:" + server.httpPort())
                    .factory(clientFactory)
                    .build()
                    .get(SCENARIO1_CLIENT1_PATH);

            final HttpResponse res2 = WebClient.builder("http://127.0.0.1:" + server.httpPort())
                    .factory(clientFactory)
                    .build()
                    .get(SCENARIO1_CLIENT2_PATH);

            assertEquals(2L, metrics.pendingRequest());
            assertEquals(0L, metrics.activeRequests());

            assertTrue(SCENARIO1_CLIENT1_ARRIVED.await(3, TimeUnit.SECONDS));
            assertTrue(SCENARIO1_CLIENT2_ARRIVED.await(3, TimeUnit.SECONDS));
            assertEquals(0L, metrics.pendingRequest());
            assertEquals(2L, metrics.activeRequests());

            SCENARIO1_CLIENT1_RESPONSE.countDown();
            res1.aggregate().join();

            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                assertEquals(1L, metrics.activeRequests());
                assertEquals(0L, metrics.pendingRequest());
            });

            SCENARIO1_CLIENT2_RESPONSE.countDown();
            res2.aggregate().join();

            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                assertEquals(0L, metrics.activeRequests());
                assertEquals(0L, metrics.pendingRequest());
            });
        }
    }

    @Test
    void client_request_early_cancel_test() throws InterruptedException {
        final ClientRequestMetrics metrics = ClientRequestLifecycleListener.counting();
        final ConnectionPoolListener delayListener = new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    // ignore
                }
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                // ignore
            }
        };

        final ClientFactoryBuilder factoryBuilder = ClientFactory
                .builder()
                .connectionPoolListener(delayListener)
                .clientRequestLifecycleListener(metrics);
        try (ClientFactory clientFactory = factoryBuilder.build();
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {

            final HttpResponse unused = WebClient.builder("http://127.0.0.1:" + server.httpPort())
                    .factory(clientFactory)
                    .build()
                    .get(SCENARIO2_PATH);

            await().pollInSameThread().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                assertEquals(1L, metrics.pendingRequest());
                assertEquals(0L, metrics.activeRequests());
            });

            // early cancel.
            captor.get().cancel(new CancellationException("test-cancel"));

            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                assertEquals(0L, metrics.activeRequests());
                assertEquals(0L, metrics.pendingRequest());
            });

            await().during(500, TimeUnit.MILLISECONDS)
                    .atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() ->
                            // because of early cancel, CountdownLatch in Server Side never be called.
                            assertEquals(1L, SCENARIO2_NEVER_CALLED.getCount())
            );
        }
    }

    @Test
    void cannot_find_proper_server_test() throws InterruptedException {
        final ClientRequestMetrics metrics = ClientRequestLifecycleListener.counting();
        final ClientFactoryBuilder factoryBuilder = ClientFactory
                .builder()
                .clientRequestLifecycleListener(metrics);
        try (ClientFactory clientFactory = factoryBuilder.build()) {
            final String invalidHostName = "http://255.255.255.255:" + server.httpPort();
            final HttpResponse res1 = WebClient.builder(invalidHostName)
                    .factory(clientFactory)
                    .build()
                    .get(SCENARIO3_PATH);

            assertEquals(1L, metrics.pendingRequest());
            assertEquals(0L, metrics.activeRequests());

            try {
                res1.aggregate().join();
            } catch (Exception e) {
                // It fails to get channel because there is no server such as http://255.255.255.255
                assertInstanceOf(CompletionException.class, e);
                assertInstanceOf(UnprocessedRequestException.class, e.getCause());
            }

            // Check clean up
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                assertEquals(0L, metrics.activeRequests());
                assertEquals(0L, metrics.pendingRequest());
            });
        }
    }
}
