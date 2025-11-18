/*
 * Copyright 2025 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.data.Percentage;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Stream;

/**
 * Makes sure Armeria HTTP server respects HTTP/2 flow control setting.
 */
public class HttpServerFlowControlTest {
    private static final Logger logger = LoggerFactory.getLogger(HttpServerFlowControlTest.class);

    private static final String PATH = "/test";
    private static final int CONNECTION_WINDOW = 1024 * 1024; // 1MB connection window
    public static final int DATA_SIZE = CONNECTION_WINDOW + 1025;
    private static final int STREAM_WINDOW = CONNECTION_WINDOW / 2; // 512KB stream window

    private static final AtomicInteger reqCounter = new AtomicInteger();
    private static CountDownLatch latch;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(LoggingService.newDecorator());
            sb.http2StreamWindowUpdateRatio(0.9f);
            sb.http2InitialConnectionWindowSize(CONNECTION_WINDOW);
            sb.http2InitialStreamWindowSize(STREAM_WINDOW);
            sb.service(PATH, (ctx, req) -> {
                final CompletableFuture<HttpResponse> future = CompletableFuture.supplyAsync(() -> {
                    final StreamingDecodedHttpRequest decodedReq = (StreamingDecodedHttpRequest) req;
                    await().untilAsserted(() -> {
                        assertThat(decodedReq.transferredBytes()).isEqualTo(STREAM_WINDOW);
                    });
                    reqCounter.incrementAndGet();
                    final InboundTrafficController controller = decodedReq.inboundTrafficController();
                    final Http2ConnectionDecoder decoder = controller.decoder();
                    final Http2LocalFlowController flowController = decoder.flowController();
                    final Http2Connection connection = decoder.connection();
                    final Http2Stream stream = connection.stream(decodedReq.streamId());
                    assertThat(flowController.windowSize(stream)).isZero();
                    assertThat(controller.isSuspended()).isFalse();
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    final AggregatedHttpRequest aggReq = req.aggregate().join();
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT,
                                           "Received: " + aggReq.content().length());
                }, ctx.blockingTaskExecutor());

                return HttpResponse.of(future);
            });

            sb.service("/stream", (ctx, req) -> {
                final CompletableFuture<HttpResponse> future = CompletableFuture.supplyAsync(() -> {
                    final AggregatedHttpRequest aggReq = req.aggregate().join();
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT,
                                           "Received: " + aggReq.content().length());
                }, ctx.blockingTaskExecutor());

                return HttpResponse.of(future);
            });

            sb.service("/aggregate", new HttpService() {

                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.UNARY;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    final CompletableFuture<HttpResponse> future = CompletableFuture.supplyAsync(() -> {
                        final AggregatingDecodedHttpRequest decodedReq = (AggregatingDecodedHttpRequest) req;
                        // non-streaming request should not be suspended by flow control.
                        assertThat(decodedReq.transferredBytes()).isEqualTo(DATA_SIZE);

                        final AggregatedHttpRequest aggReq = req.aggregate().join();
                        return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT,
                                               "Received: " + aggReq.content().length());
                    }, ctx.blockingTaskExecutor());

                    return HttpResponse.of(future);
                }
            });
        }
    };

    @BeforeEach
    void setUp() {
        reqCounter.set(0);
        latch = new CountDownLatch(1);
    }

    @Test
    void flowControl() throws Exception {
        final WebClient client = WebClient.of(server.uri(SessionProtocol.H2C));

        final CompletableFuture<AggregatedHttpResponse> res1 =
                client.post(PATH, HttpData.wrap(new byte[DATA_SIZE])).aggregate();
        await().untilAtomic(reqCounter, Matchers.is(1));

        final CompletableFuture<AggregatedHttpResponse> res2 =
                client.post(PATH, HttpData.wrap(new byte[DATA_SIZE])).aggregate();
        await().untilAtomic(reqCounter, Matchers.is(2));

        final ServiceRequestContext sctx2 = server.requestContextCaptor().take();
        final StreamingDecodedHttpRequest decodedServerReq = (StreamingDecodedHttpRequest) sctx2.request();
        final Http2ConnectionDecoder decoder = decodedServerReq.inboundTrafficController().decoder();
        final Http2LocalFlowController flowController = decoder.flowController();
        final Http2Connection connection = decoder.connection();
        assertThat(flowController.windowSize(connection.connectionStream())).isEqualTo(0);

        logger.debug("Start aggregating the responses to release the flow control window.");
        latch.countDown();
        final AggregatedHttpResponse aggRes1 = res1.join();
        assertThat(aggRes1.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggRes1.contentUtf8()).isEqualTo("Received: " + DATA_SIZE);

        final AggregatedHttpResponse aggRes2 = res2.join();
        assertThat(aggRes2.status()).isEqualTo(HttpStatus.OK);
        assertThat(aggRes2.contentUtf8()).isEqualTo("Received: " + DATA_SIZE);

        assertThat(flowController.windowSize(connection.connectionStream())).isGreaterThan(0);
    }

    @Test
    void flowControl_with_rejected_streams() throws Exception {
        final WebClient client = WebClient.of(server.uri(SessionProtocol.H2C));
        for (int i = 0; i < 10; i++) {
            final CompletableFuture<AggregatedHttpResponse> res1 =
                    client.post("/stream", HttpData.wrap(new byte[DATA_SIZE])).aggregate();

            final CompletableFuture<AggregatedHttpResponse> res2 =
                    client.prepare()
                          .post("/stream")
                          .content(MediaType.OCTET_STREAM, HttpData.wrap(new byte[DATA_SIZE]))
                          // Make the request fail before increasing the request ID.
                          .header(HttpHeaderNames.EXPECT, "invalid")
                          .execute()
                          .aggregate();

            final AggregatedHttpResponse aggRes1 = res1.join();
            assertThat(aggRes1.status()).isEqualTo(HttpStatus.OK);
            assertThat(aggRes1.contentUtf8()).isEqualTo("Received: " + DATA_SIZE);

            final AggregatedHttpResponse aggRes2 = res2.join();
            assertThat(aggRes2.status()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
        }

        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        final StreamingDecodedHttpRequest decodedServerReq = (StreamingDecodedHttpRequest) sctx.request();
        final Http2ConnectionDecoder decoder = decodedServerReq.inboundTrafficController().decoder();
        final Http2LocalFlowController flowController = decoder.flowController();
        final Http2Connection connection = decoder.connection();
        assertThat(flowController.windowSize(connection.connectionStream()))
                .isCloseTo(CONNECTION_WINDOW, Percentage.withPercentage(10));
    }

    @Test
    void ignoreFlowControlForNonStreamRequest() throws Exception {
        final WebClient client = WebClient.of(server.uri(SessionProtocol.H2C));

        final AggregatedHttpResponse res =
                client.post("/aggregate", HttpData.wrap(new byte[DATA_SIZE])).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("Received: " + DATA_SIZE);
    }

    @Test
    void testStreamWindowUpdateRatio() {
        assertThatThrownBy(() -> Server.builder().http2StreamWindowUpdateRatio(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("http2StreamWindowUpdateRatio: 0.0 (expected: > 0 and < 1.0)");

        assertThatThrownBy(() -> Server.builder().http2StreamWindowUpdateRatio(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("http2StreamWindowUpdateRatio: 1.0 (expected: > 0 and < 1.0)");

        assertThatCode(() -> {
            Server.builder().http2StreamWindowUpdateRatio(0.5f);
        }).doesNotThrowAnyException();
    }
}
