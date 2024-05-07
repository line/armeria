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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

class ExceedingServiceMaxContentLengthTest {

    private static final AtomicReference<Throwable> responseCause = new AtomicReference<>();

    private static final Queue<ByteBuf> byteBufs = new ArrayBlockingQueue<>(8);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.childChannelPipelineCustomizer(pipeline -> {
                pipeline.addFirst(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(@NotNull ChannelHandlerContext ctx, @NotNull Object msg)
                            throws Exception {
                        assert msg instanceof ByteBuf;
                        super.channelRead(ctx, msg);
                        byteBufs.add((ByteBuf) msg);
                    }
                });
            });
            sb.maxRequestLength(100);
            final LogWriter logWriter = LogWriter.builder().logFormatter(new LogFormatter() {
                @Override
                public String formatRequest(RequestOnlyLog log) {
                    return "null";
                }

                @Override
                public String formatResponse(RequestLog log) {
                    responseCause.set(log.responseCause());
                    return "null";
                }
            }).build();
            sb.decorator(LoggingService.builder().logWriter(logWriter).newDecorator());
            sb.decorator((delegate, ctx, req) -> {
                ctx.addAdditionalResponseHeader("additional", "header");
                return delegate.serve(ctx, req);
            });
            sb.service("/streaming", (ctx, req) -> {
                final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                req.aggregate().handle((unused, cause) -> {
                    future.complete(HttpResponse.of("Hello, world!"));
                    return null;
                });
                return HttpResponse.of(future);
            });
            sb.service("/unary", new HttpService() {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(req.aggregate().thenApply(agg -> {
                        return HttpResponse.of("Hello, world!");
                    }));
                }

                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.UNARY;
                }
            });
        }
    };

    @BeforeEach
    void setUp() {
        byteBufs.clear();
    }

    @CsvSource({
            "H1C, /streaming",
            "H1C, /unary",
            "H2C, /streaming",
            "H2C, /unary",
    })
    @ParameterizedTest
    void maxContentLength(SessionProtocol protocol, String path) throws InterruptedException {
        final HttpRequestWriter streaming = HttpRequest.streaming(HttpMethod.POST, path);
        for (int i = 0; i < 4; i++) {
            streaming.write(HttpData.ofUtf8(Strings.repeat("a", 30)));
        }
        streaming.close();
        final AggregatedHttpResponse response = WebClient.of(server.uri(protocol))
                                                         .execute(streaming)
                                                         .aggregate()
                                                         .join();
        assertThat(response.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(response.headers().get("additional")).isEqualTo("header");
        assertThat(response.contentUtf8()).startsWith("Status: 413\n");
        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        final RequestLog log = sctx.log().whenComplete().join();
        // Make sure that the response was correctly logged.
        assertThat(log.responseStatus()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        // Make sure that LoggingService is called.
        await().untilAsserted(
                () -> assertThat(responseCause.get()).isExactlyInstanceOf(ContentTooLargeException.class));

        await().untilAsserted(() -> assertThat(byteBufs).allSatisfy(buf -> assertThat(buf.refCnt()).isZero()));
    }
}
