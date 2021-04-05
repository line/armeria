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
package com.linecorp.armeria.server;

import static com.linecorp.armeria.common.SessionProtocol.H1;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

class HttpServerStreamingTest {
    private static final Logger logger = LoggerFactory.getLogger(HttpServerStreamingTest.class);

    private static final EventLoopGroup workerGroup = EventLoopGroups.newEventLoopGroup(1);

    private static final ClientFactory clientFactory =
            ClientFactory.builder()
                         .workerGroup(workerGroup, false) // Will be shut down by the Server.
                         .idleTimeout(Duration.ofSeconds(3))
                         .tlsNoVerify()
                         .build();

    // Stream as much as twice of the heap.
    private static final long STREAMING_CONTENT_LENGTH = Runtime.getRuntime().maxMemory();
    private static final int STREAMING_CONTENT_CHUNK_LENGTH =
            (int) Math.min(Integer.MAX_VALUE, STREAMING_CONTENT_LENGTH / 8);

    private static volatile long serverMaxRequestLength;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            sb.workerGroup(workerGroup, true);
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            sb.service("/count", new CountingService(false));
            sb.service("/slow_count", new CountingService(true));

            sb.serviceUnder("/zeroes", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final long length = Long.parseLong(ctx.mappedPath().substring(1));
                    final HttpResponseWriter res = HttpResponse.streaming();
                    res.write(ResponseHeaders.of(HttpStatus.OK,
                                                 HttpHeaderNames.CONTENT_LENGTH, length));

                    stream(res, length, STREAMING_CONTENT_CHUNK_LENGTH);
                    return res;
                }
            });

            final Function<? super HttpService, ? extends HttpService> decorator =
                    s -> new SimpleDecoratingHttpService(s) {
                        @Override
                        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                            ctx.setMaxRequestLength(serverMaxRequestLength);
                            return unwrap().serve(ctx, req);
                        }
                    };
            sb.decorator(decorator);

            sb.maxRequestLength(0);
            sb.requestTimeoutMillis(0);
            sb.idleTimeout(Duration.ofSeconds(5));
        }
    };

    @AfterAll
    static void destroy() {
        clientFactory.closeAsync();
    }

    @BeforeEach
    void resetOptions() {
        serverMaxRequestLength = 0;
    }

    @ParameterizedTest
    @ArgumentsSource(ClientProvider.class)
    void testTooLargeContent(WebClient client) throws Exception {
        final int maxContentLength = 65536;
        serverMaxRequestLength = maxContentLength;

        final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.POST, "/count");
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(req).aggregate();

        stream(req, maxContentLength + 1, 1024);

        final AggregatedHttpResponse res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("413 Request Entity Too Large");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientProvider.class)
    void testTooLargeContentToNonExistentService(WebClient client) throws Exception {
        final int maxContentLength = 65536;
        serverMaxRequestLength = maxContentLength;

        final byte[] content = new byte[maxContentLength + 1];
        if (client.scheme().sessionProtocol().uriText().startsWith("h1")) {
            // Unlike HTTP/2, ClosedSessionException is raised because Http1RequestDecoder closes
            // the connection before the content of "404 Not Found" is sent.
            //
            // When the Http1RequestDecoder notices that the request entity is too large, the only thing it
            // can do is that just closing the connection if the response headers (e.g 404 in this case)
            // is already sent.
            // If we wait for the response to be sent fully and close the connection, then the subsequent
            // following request that uses the same connection will encounter ClosedSessionException which
            // is undesirable.
            // However, HTTP/2 can wait for the response to be sent, because the following request uses the
            // next stream.
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                assertThatThrownBy(() -> client.post("/non-existent", content).aggregate().join())
                        .hasCauseInstanceOf(ClosedSessionException.class);
                final ResponseHeaders responseHeaders =
                        captor.get().log().ensureAvailable(RequestLogProperty.RESPONSE_HEADERS)
                              .responseHeaders();
                // Even though the request is failed, the client got the 404 response headers.
                assertThat(responseHeaders.status()).isSameAs(HttpStatus.NOT_FOUND);
            }
        } else {
            final AggregatedHttpResponse res = client.post("/non-existent", content).aggregate().get();
            assertThat(res.status()).isSameAs(HttpStatus.NOT_FOUND);
            assertThat(res.contentUtf8()).isEqualTo("404 Not Found");
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ClientProvider.class)
    void testStreamingRequest(WebClient client) throws Exception {
        runStreamingRequestTest(client, "/count");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientProvider.class)
    void testStreamingRequestWithSlowService(WebClient client) throws Exception {
        final int oldNumDeferredReads = InboundTrafficController.numDeferredReads();
        runStreamingRequestTest(client, "/slow_count");
        // The connection's inbound traffic must be suspended due to overwhelming traffic from client.
        // If the number of deferred reads did not increase and the testStreaming() above did not fail,
        // it probably means the client failed to produce enough amount of traffic.
        assertThat(InboundTrafficController.numDeferredReads()).isGreaterThan(oldNumDeferredReads);
    }

    private static void runStreamingRequestTest(WebClient client, String path)
            throws InterruptedException, ExecutionException {
        final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.POST, path);
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(req).aggregate();

        // Stream a large of the max memory.
        // This test will fail if the implementation keep the whole content in memory.
        final long expectedContentLength = STREAMING_CONTENT_LENGTH;

        try {
            stream(req, expectedContentLength, STREAMING_CONTENT_CHUNK_LENGTH);

            final AggregatedHttpResponse res = f.get();

            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
            assertThat(res.contentUtf8()).isEqualTo(
                    String.valueOf(expectedContentLength));
        } finally {
            // Make sure the stream is closed even when this test fails due to timeout.
            req.close();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ClientProvider.class)
    void testStreamingResponse(WebClient client) throws Exception {
        runStreamingResponseTest(client, false);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientProvider.class)
    void testStreamingResponseWithSlowClient(WebClient client) throws Exception {
        final int oldNumDeferredReads = InboundTrafficController.numDeferredReads();
        runStreamingResponseTest(client, true);
        // The connection's inbound traffic must be suspended due to overwhelming traffic from client.
        // If the number of deferred reads did not increase and the testStreaming() above did not fail,
        // it probably means the client failed to produce enough amount of traffic.
        assertThat(InboundTrafficController.numDeferredReads()).isGreaterThan(oldNumDeferredReads);
    }

    private static void runStreamingResponseTest(WebClient client, boolean slowClient)
            throws InterruptedException, ExecutionException {
        final HttpResponse res = client.get("/zeroes/" + STREAMING_CONTENT_LENGTH);
        final AtomicReference<HttpStatus> status = new AtomicReference<>();

        final StreamConsumer consumer = new StreamConsumer(GlobalEventExecutor.INSTANCE, slowClient) {

            @Override
            public void onNext(HttpObject obj) {
                if (obj instanceof ResponseHeaders) {
                    status.compareAndSet(null, ((ResponseHeaders) obj).status());
                }
                super.onNext(obj);
            }

            @Override
            public void onError(Throwable cause) {
                // Will be notified via the 'awaitClose().get()' below.
            }

            @Override
            public void onComplete() {}
        };

        res.subscribe(consumer);

        res.whenComplete().get();
        assertThat(status.get()).isEqualTo(HttpStatus.OK);
        assertThat(consumer.numReceivedBytes()).isEqualTo(STREAMING_CONTENT_LENGTH);
    }

    private static void stream(StreamWriter<HttpObject> writer, long size, int chunkSize) {
        if (!writer.tryWrite(HttpData.wrap(new byte[chunkSize]))) {
            return;
        }

        final long remaining = size - chunkSize;
        logger.info("{} bytes remaining", remaining);

        if (remaining == 0) {
            writer.close();
            return;
        }

        writer.whenConsumed()
              .thenRun(() -> stream(writer, remaining, (int) Math.min(remaining, chunkSize)))
              .exceptionally(cause -> {
                  logger.warn("Unexpected exception:", cause);
                  writer.close(cause);
                  return null;
              });
    }

    private static class ClientProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(H1C, H2C, H1, H2)
                         .map(protocol -> {
                             final WebClientBuilder builder = WebClient.builder(
                                     protocol.uriText() + "://127.0.0.1:" +
                                     (protocol.isTls() ? server.httpsPort() : server.httpPort()));

                             builder.factory(clientFactory);
                             builder.responseTimeoutMillis(0);
                             builder.maxResponseLength(0);

                             return builder.build();
                         })
                         .map(Arguments::of);
        }
    }

    private static class CountingService extends AbstractHttpService {

        private final boolean slow;

        CountingService(boolean slow) {
            this.slow = slow;
        }

        @Override
        protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
            final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
            final HttpResponse res = HttpResponse.from(responseFuture);
            req.subscribe(new StreamConsumer(ctx.eventLoop(), slow) {
                @Override
                public void onError(Throwable cause) {
                    responseFuture.complete(
                            HttpResponse.of(
                                    HttpStatus.INTERNAL_SERVER_ERROR,
                                    MediaType.PLAIN_TEXT_UTF_8,
                                    Exceptions.traceText(cause)));
                }

                @Override
                public void onComplete() {
                    responseFuture.complete(
                            HttpResponse.of(
                                    HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "%d", numReceivedBytes()));
                }
            });
            return res;
        }
    }

    private abstract static class StreamConsumer implements Subscriber<HttpObject> {
        private final ScheduledExecutorService executor;
        private final boolean slow;

        private Subscription subscription;
        private long numReceivedBytes;
        private int numReceivedChunks;

        protected StreamConsumer(ScheduledExecutorService executor, boolean slow) {
            this.executor = executor;
            this.slow = slow;
        }

        protected long numReceivedBytes() {
            return numReceivedBytes;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(HttpObject obj) {
            if (obj instanceof HttpData) {
                numReceivedBytes += ((HttpData) obj).length();
            }

            if (numReceivedBytes >= (numReceivedChunks + 1L) * STREAMING_CONTENT_CHUNK_LENGTH) {
                numReceivedChunks++;

                if (slow) {
                    // Add 1 second delay for every chunk received.
                    executor.schedule(() -> subscription.request(1), 1, TimeUnit.SECONDS);
                } else {
                    subscription.request(1);
                }

                logger.debug("{} bytes received", numReceivedBytes);
            } else {
                subscription.request(1);
            }
        }
    }
}
