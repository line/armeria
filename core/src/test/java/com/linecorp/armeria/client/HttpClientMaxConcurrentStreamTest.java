/*
 * Copyright 2018 LINE Corporation
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.EventLoopGroup;
import io.netty.util.AttributeMap;

/**
 * Makes sure Armeria HTTP client respects {@code MAX_CONCURRENT_STREAMS} HTTP/2 setting.
 */
public class HttpClientMaxConcurrentStreamTest {

    private static final String PATH = "/test";
    private static final int MAX_CONCURRENT_STREAMS = 3;
    private static final int MAX_NUM_CONNECTIONS = 6;

    static final Queue<CompletableFuture<HttpResponse>> responses = new ConcurrentLinkedQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(PATH, (ctx, req) -> {
                final CompletableFuture<HttpResponse> f = new CompletableFuture<>();
                responses.add(f);
                return HttpResponse.from(f);
            });
            sb.http2MaxStreamsPerConnection(MAX_CONCURRENT_STREAMS);
            sb.maxNumConnections(MAX_NUM_CONNECTIONS);
            sb.idleTimeoutMillis(3000);
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithMaxConcurrentStreams1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(PATH, (ctx, req) -> {
                final CompletableFuture<HttpResponse> f = new CompletableFuture<>();
                responses.add(f);
                return HttpResponse.from(f);
            });
            sb.http2MaxStreamsPerConnection(1);
            sb.maxNumConnections(MAX_NUM_CONNECTIONS);
            sb.idleTimeoutMillis(3000);
        }
    };

    private final ConnectionPoolListener connectionPoolListenerWrapper = new ConnectionPoolListener() {
        @Override
        public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                   InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
            final ConnectionPoolListener connectionPoolListener =
                    HttpClientMaxConcurrentStreamTest.this.connectionPoolListener;
            if (connectionPoolListener != null) {
                connectionPoolListener.connectionOpen(protocol, remoteAddr, localAddr, attrs);
            }
        }

        @Override
        public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                     InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
            final ConnectionPoolListener connectionPoolListener =
                    HttpClientMaxConcurrentStreamTest.this.connectionPoolListener;
            if (connectionPoolListener != null) {
                connectionPoolListener.connectionClosed(protocol, remoteAddr, localAddr, attrs);
            }
        }
    };

    @Nullable
    private ClientFactory clientFactory;
    @Nullable
    private volatile ConnectionPoolListener connectionPoolListener;

    @BeforeEach
    void setUp() {
        clientFactory = ClientFactory.builder()
                                     .workerGroup(1)
                                     .connectionPoolListener(connectionPoolListenerWrapper)
                                     .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Complete all uncompleted requests.
        for (;;) {
            final CompletableFuture<HttpResponse> f = responses.poll();
            if (f == null) {
                break;
            }
            f.complete(HttpResponse.of(200));
        }

        if (clientFactory != null) {
            clientFactory.close();
        }

        await().until(() -> server.server().numConnections() == 0);
        await().until(() -> serverWithMaxConcurrentStreams1.server().numConnections() == 0);
    }

    @Test
    void shouldCreateConnectionWhenExceedsMaxConcurrentStreams() throws Exception {
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                          .factory(clientFactory)
                                          .build();
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        connectionPoolListener = newConnectionPoolListener(opens::incrementAndGet, closes::incrementAndGet);

        // Send (2 * MAX_CONCURRENT_STREAMS) requests to create 2 connections, never more and never less.
        final List<CompletableFuture<AggregatedHttpResponse>> receivedResponses = new ArrayList<>();
        final int NUM_CONNECTIONS = 2;
        for (int j = 0; j < NUM_CONNECTIONS; j++) {
            final int expectedOpens = j + 1;
            for (int i = 0; i < MAX_CONCURRENT_STREAMS; i++) {
                // Send a request.
                receivedResponses.add(client.get(PATH).aggregate());

                // Check the number of open and closed connections.
                await().untilAsserted(() -> {
                    assertThat(opens).hasValue(expectedOpens);
                    assertThat(closes).hasValue(0);
                });
            }
        }

        // Complete one request so that we have a connection with (MAX_CONCURRENT_STREAM - 1) active streams.
        responses.poll().complete(HttpResponse.of(200));
        await().until(() -> receivedResponses.stream().anyMatch(CompletableFuture::isDone));

        // Send a new request, which must not create a new connection but use an existing connection.
        client.get(PATH).aggregate();

        // Wait for a while to make sure no new connection is created.
        for (int i = 0; i < 3; i++) {
            Thread.sleep(1000);
            assertThat(opens).hasValue(NUM_CONNECTIONS);
            assertThat(closes).hasValue(0);
        }
    }

    @Test
    void handleExceedsMaxStreamsBasicCase() throws Exception {
        final Queue<ClientConnectionTimings> connectionTimings = new ConcurrentLinkedQueue<>();
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                          .factory(clientFactory)
                                          .decorator(connectionTimingsAccumulatingDecorator(connectionTimings))
                                          .build();
        final int numRequests = MAX_CONCURRENT_STREAMS + 1;

        runInsideEventLoop(clientFactory.eventLoopGroup(), () -> {
            for (int i = 0; i < numRequests; i++) {
                client.get(PATH).aggregate();
            }
        });

        await().untilAsserted(() -> assertThat(responses).hasSize(numRequests));
        assertThat(connectionTimings.stream().filter(
                timings -> timings.pendingAcquisitionDurationNanos() > 0))
                .hasSize(numRequests - 1);
    }

    @Test
    void openMinimalConnectionsWhenExceededMaxStreams() throws Exception {
        final Queue<ClientConnectionTimings> connectionTimings = new ConcurrentLinkedQueue<>();
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                          .factory(clientFactory)
                                          .decorator(connectionTimingsAccumulatingDecorator(connectionTimings))
                                          .build();
        final AtomicInteger opens = new AtomicInteger();
        connectionPoolListener = newConnectionPoolListener(opens::incrementAndGet, () -> {});

        final int numExpectedConnections = MAX_NUM_CONNECTIONS;
        final int numRequests = MAX_CONCURRENT_STREAMS * numExpectedConnections;

        runInsideEventLoop(clientFactory.eventLoopGroup(), () -> {
            for (int i = 0; i < numRequests; i++) {
                client.get(PATH).aggregate();
            }
        });

        await().untilAsserted(() -> assertThat(responses).hasSize(numRequests));
        assertThat(opens).hasValue(numExpectedConnections);
        assertThat(connectionTimings.stream().filter(
                timings -> timings.pendingAcquisitionDurationNanos() > 0))
                .hasSize(numRequests - 1);
    }

    @Test
    void exceededMaxStreamsPropagatesFailureCorrectly() throws Exception {
        final Queue<ClientConnectionTimings> connectionTimings = new ConcurrentLinkedQueue<>();
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                          .factory(clientFactory)
                                          .decorator(connectionTimingsAccumulatingDecorator(connectionTimings))
                                          .build();
        final AtomicInteger opens = new AtomicInteger();
        connectionPoolListener = newConnectionPoolListener(opens::incrementAndGet, () -> {});
        final List<CompletableFuture<AggregatedHttpResponse>> receivedResponses = new ArrayList<>();

        final int numExpectedConnections = MAX_NUM_CONNECTIONS;
        final int numRequests = MAX_CONCURRENT_STREAMS * numExpectedConnections;
        final int numFailedRequests = MAX_CONCURRENT_STREAMS - 1;

        runInsideEventLoop(clientFactory.eventLoopGroup(), () -> {
            for (int i = 0; i < numRequests + numFailedRequests; i++) {
                receivedResponses.add(client.get(PATH).aggregate());
            }
        });

        await().untilAsserted(() -> assertThat(responses).hasSize(numRequests));
        await().until(() -> receivedResponses.stream().filter(CompletableFuture::isCompletedExceptionally)
                                             .count() == numFailedRequests);
        assertThat(opens).hasValue(numExpectedConnections);
        assertThat(connectionTimings.stream().filter(
                timings -> timings.pendingAcquisitionDurationNanos() > 0))
                .hasSize(numRequests + numFailedRequests - 1);

        // Check exception thrown by responses
        await().untilAsserted(() -> assertThat(receivedResponses.stream().filter(
                CompletableFuture::isCompletedExceptionally)).hasSize(2));

        receivedResponses
                .stream().filter(CompletableFuture::isCompletedExceptionally)
                .forEach(responseFuture -> {
                    final Throwable throwable = catchThrowable(responseFuture::join);
                    assertThat(throwable).isInstanceOf(CompletionException.class)
                                         .hasCauseInstanceOf(UnprocessedRequestException.class);
                    assertThat(throwable.getCause().getCause()).satisfiesAnyOf(
                            e -> assertThat(e).isInstanceOf(ClosedSessionException.class),
                            e -> assertThat(e).isInstanceOf(ConnectException.class)
                                              .hasMessageContaining("reset by peer"),
                            e -> assertThat(e).isInstanceOf(SocketException.class)
                                              .hasMessageContaining("reset by peer"));
                });
    }

    @Test
    void exceededMaxStreamsForMultipleEventLoops() {
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .connectionPoolListener(connectionPoolListenerWrapper)
                                  .maxNumEventLoopsPerEndpoint(2)
                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                              .factory(clientFactory)
                                              .build();
            final AtomicInteger opens = new AtomicInteger();
            connectionPoolListener = newConnectionPoolListener(opens::incrementAndGet, () -> {
            });

            final int numExpectedConnections = MAX_NUM_CONNECTIONS;
            final int numRequests = MAX_CONCURRENT_STREAMS * numExpectedConnections;

            runInsideEventLoop(clientFactory.eventLoopGroup(), () -> {
                for (int i = 0; i < numRequests; i++) {
                    client.get(PATH).aggregate();
                }
            });

            await().untilAsserted(() -> assertThat(responses).hasSize(numRequests));
            assertThat(opens).hasValue(numExpectedConnections);
        }
    }

    @Test
    void ensureCorrectPendingAcquisitionDurationBehavior() throws Exception {
        final Queue<ClientConnectionTimings> connectionTimings = new ConcurrentLinkedQueue<>();
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                          .factory(clientFactory)
                                          .decorator(connectionTimingsAccumulatingDecorator(connectionTimings))
                                          .build();
        final int sleepMillis = 300;
        connectionPoolListener = newConnectionPoolListener(() -> {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }, () -> {});

        final int numConnections = MAX_NUM_CONNECTIONS;
        final int numRequests = MAX_CONCURRENT_STREAMS * numConnections;

        runInsideEventLoop(clientFactory.eventLoopGroup(), () -> {
            for (int i = 0; i < numRequests; i++) {
                client.get(PATH).aggregate();
            }
        });

        await().untilAsserted(() -> assertThat(responses).hasSize(numRequests));
        assertThat(connectionTimings.stream().filter(
                timings -> timings.pendingAcquisitionDurationNanos() > 0))
                .hasSize(numRequests - 1);

        // There should be at least one request with at least numConnections * pendingAcquisitionsDuration
        final Long maxPendingAcquisitionDurationNanos = connectionTimings.stream().mapToLong(
                ClientConnectionTimings::pendingAcquisitionDurationNanos).max().orElse(0L);
        assertThat(maxPendingAcquisitionDurationNanos)
                .isGreaterThan(TimeUnit.MILLISECONDS.toNanos(sleepMillis * numConnections));
    }

    @Test
    void maxConcurrentStreamsValue_1() throws Exception {
        final Queue<ClientConnectionTimings> connectionTimings = new ConcurrentLinkedQueue<>();
        final WebClient client = WebClient.builder(serverWithMaxConcurrentStreams1.uri(SessionProtocol.H2C))
                                          .factory(clientFactory)
                                          .decorator(connectionTimingsAccumulatingDecorator(connectionTimings))
                                          .build();
        final AtomicInteger opens = new AtomicInteger();
        connectionPoolListener = newConnectionPoolListener(opens::incrementAndGet, () -> {});

        final int numExpectedConnections = 6;
        final int maxConcurrentStreams = 1;
        final int numRequests = maxConcurrentStreams * numExpectedConnections;

        runInsideEventLoop(clientFactory.eventLoopGroup(), () -> {
            for (int i = 0; i < numRequests; i++) {
                client.get(PATH).aggregate();
            }
        });

        await().untilAsserted(() -> assertThat(responses).hasSize(numRequests));
        assertThat(opens).hasValue(numExpectedConnections);
        assertThat(connectionTimings.stream().filter(
                timings -> timings.pendingAcquisitionDurationNanos() > 0))
                .hasSize(numRequests - 1);
    }

    @Test
    void allStreamsUsedOnEarlyResponseClose() throws Exception {
        final Queue<ClientConnectionTimings> connectionTimings = new ConcurrentLinkedQueue<>();
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                          .factory(clientFactory)
                                          .decorator(connectionTimingsAccumulatingDecorator(connectionTimings))
                                          .build();
        final AtomicInteger opens = new AtomicInteger();
        connectionPoolListener = newConnectionPoolListener(opens::incrementAndGet, () -> {});
        final int numExpectedConnections = 1;

        // queue a request which is closed before headers are written
        final List<HttpResponse> abortedResponses = new ArrayList<>();
        runInsideEventLoop(clientFactory.eventLoopGroup(), () -> {
            for (int i = 0; i < 2; i++) {
                final HttpResponse httpResponse = client.get(PATH);
                httpResponse.abort();
                abortedResponses.add(httpResponse);
            }
        });
        await().until(() -> abortedResponses.stream().allMatch(HttpResponse::isComplete));

        // unfinishedResponses should be 0 for the connection now
        runInsideEventLoop(clientFactory.eventLoopGroup(), () -> {
            for (int i = 0; i < MAX_CONCURRENT_STREAMS; i++) {
                client.get(PATH).aggregate();
            }
        });

        await().untilAsserted(() -> assertThat(responses).hasSize(MAX_CONCURRENT_STREAMS));
        assertThat(opens).hasValue(numExpectedConnections);
    }

    // running inside an event loop ensures requests are queued before an initial connect attempt completes.
    private static void runInsideEventLoop(EventLoopGroup eventLoopGroup, Runnable runnable) {
        eventLoopGroup.execute(runnable);
    }

    private static DecoratingHttpClientFunction connectionTimingsAccumulatingDecorator(
            Queue<ClientConnectionTimings> connectionTimings) {
        return (delegate, ctx, req) -> {
            ctx.logBuilder().whenAvailable(RequestLogProperty.SESSION)
               .thenAccept(requestLog -> {
                   connectionTimings.add(requestLog.connectionTimings());
               });
            return delegate.execute(ctx, req);
        };
    }

    private static ConnectionPoolListener newConnectionPoolListener(
            Runnable openRunnable, Runnable closeRunnable) {
        return new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                openRunnable.run();
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                closeRunnable.run();
            }
        };
    }
}
