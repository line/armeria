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

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.InboundTrafficController;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.GlobalEventExecutor;

@RunWith(Parameterized.class)
public class HttpServerStreamingTest {
    private static final Logger logger = LoggerFactory.getLogger(HttpServerStreamingTest.class);

    private static final EventLoopGroup workerGroup = EventLoopGroups.newEventLoopGroup(1);

    private static final ClientFactory clientFactory = new ClientFactoryBuilder()
            .workerGroup(workerGroup, false) // Will be shut down by the Server.
            .idleTimeout(Duration.ofSeconds(3))
            .sslContextCustomizer(b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE))
            .build();

    // Stream as much as twice of the heap.
    private static final long STREAMING_CONTENT_LENGTH = Runtime.getRuntime().maxMemory() * 2;
    private static final int STREAMING_CONTENT_CHUNK_LENGTH =
            (int) Math.min(Integer.MAX_VALUE, STREAMING_CONTENT_LENGTH / 8);

    @Parameters(name = "{index}: {0}")
    public static Collection<SessionProtocol> parameters() {
        return ImmutableList.of(H1C, H1, H2C, H2);
    }

    private static volatile long serverMaxRequestLength;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
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
                    res.write(HttpHeaders.of(HttpStatus.OK)
                                         .setLong(HttpHeaderNames.CONTENT_LENGTH, length));

                    stream(res, length, STREAMING_CONTENT_CHUNK_LENGTH);
                    return res;
                }
            });

            final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>>
                    decorator =
                    s -> new SimpleDecoratingService<HttpRequest, HttpResponse>(s) {
                        @Override
                        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                            ctx.setMaxRequestLength(serverMaxRequestLength);
                            return delegate().serve(ctx, req);
                        }
                    };
            sb.decorator(decorator);

            sb.defaultMaxRequestLength(0);
            sb.defaultRequestTimeoutMillis(0);
            sb.idleTimeout(Duration.ofSeconds(5));
        }
    };

    private final SessionProtocol protocol;
    private HttpClient client;

    public HttpServerStreamingTest(SessionProtocol protocol) {
        this.protocol = protocol;
    }

    @AfterClass
    public static void destroy() {
        CompletableFuture.runAsync(clientFactory::close);
    }

    @Before
    public void resetOptions() {
        serverMaxRequestLength = 0;
    }

    @Test(timeout = 10000)
    public void testTooLargeContent() throws Exception {
        final int maxContentLength = 65536;
        serverMaxRequestLength = maxContentLength;

        final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.POST, "/count");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        stream(req, maxContentLength + 1, 1024);

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("413 Request Entity Too Large");
    }

    @Test(timeout = 10000)
    public void testTooLargeContentToNonExistentService() throws Exception {
        final int maxContentLength = 65536;
        serverMaxRequestLength = maxContentLength;

        final byte[] content = new byte[maxContentLength + 1];
        final AggregatedHttpMessage res = client().post("/non-existent", content).aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.contentUtf8()).isEqualTo("404 Not Found");
    }

    @Test(timeout = 60000)
    public void testStreamingRequest() throws Exception {
        runStreamingRequestTest("/count");
    }

    @Test(timeout = 120000)
    public void testStreamingRequestWithSlowService() throws Exception {
        final int oldNumDeferredReads = InboundTrafficController.numDeferredReads();
        runStreamingRequestTest("/slow_count");
        // The connection's inbound traffic must be suspended due to overwhelming traffic from client.
        // If the number of deferred reads did not increase and the testStreaming() above did not fail,
        // it probably means the client failed to produce enough amount of traffic.
        assertThat(InboundTrafficController.numDeferredReads()).isGreaterThan(oldNumDeferredReads);
    }

    private void runStreamingRequestTest(String path) throws InterruptedException, ExecutionException {
        final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.POST, path);
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        // Stream a large of the max memory.
        // This test will fail if the implementation keep the whole content in memory.
        final long expectedContentLength = STREAMING_CONTENT_LENGTH;

        try {
            stream(req, expectedContentLength, STREAMING_CONTENT_CHUNK_LENGTH);

            final AggregatedHttpMessage res = f.get();

            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
            assertThat(res.contentUtf8()).isEqualTo(
                    String.valueOf(expectedContentLength));
        } finally {
            // Make sure the stream is closed even when this test fails due to timeout.
            req.close();
        }
    }

    @Test(timeout = 60000)
    public void testStreamingResponse() throws Exception {
        runStreamingResponseTest(false);
    }

    @Test(timeout = 120000)
    public void testStreamingResponseWithSlowClient() throws Exception {
        final int oldNumDeferredReads = InboundTrafficController.numDeferredReads();
        runStreamingResponseTest(true);
        // The connection's inbound traffic must be suspended due to overwhelming traffic from client.
        // If the number of deferred reads did not increase and the testStreaming() above did not fail,
        // it probably means the client failed to produce enough amount of traffic.
        assertThat(InboundTrafficController.numDeferredReads()).isGreaterThan(oldNumDeferredReads);
    }

    private void runStreamingResponseTest(boolean slowClient) throws InterruptedException, ExecutionException {
        final HttpResponse res = client().get("/zeroes/" + STREAMING_CONTENT_LENGTH);
        final AtomicReference<HttpStatus> status = new AtomicReference<>();

        final StreamConsumer consumer = new StreamConsumer(GlobalEventExecutor.INSTANCE, slowClient) {

            @Override
            public void onNext(HttpObject obj) {
                if (obj instanceof HttpHeaders) {
                    status.compareAndSet(null, ((HttpHeaders) obj).status());
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

        res.completionFuture().get();
        assertThat(status.get()).isEqualTo(HttpStatus.OK);
        assertThat(consumer.numReceivedBytes()).isEqualTo(STREAMING_CONTENT_LENGTH);
    }

    private static void stream(StreamWriter<HttpObject> writer, long size, int chunkSize) {
        if (!writer.tryWrite(HttpData.of(new byte[chunkSize]))) {
            return;
        }

        final long remaining = size - chunkSize;
        logger.info("{} bytes remaining", remaining);

        if (remaining == 0) {
            writer.close();
            return;
        }

        writer.onDemand(() -> stream(writer, remaining, (int) Math.min(remaining, chunkSize)))
              .exceptionally(cause -> {
                  logger.warn("Unexpected exception:", cause);
                  writer.close(cause);
                  return null;
              });
    }

    private HttpClient client() {
        if (client != null) {
            return client;
        }

        final HttpClientBuilder builder = new HttpClientBuilder(
                protocol.uriText() + "://127.0.0.1:" +
                (protocol.isTls() ? server.httpsPort() : server.httpPort()));

        builder.factory(clientFactory);
        builder.defaultResponseTimeoutMillis(0);
        builder.defaultMaxResponseLength(0);

        return client = builder.build();
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
