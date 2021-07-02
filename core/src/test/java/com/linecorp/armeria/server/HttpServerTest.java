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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.common.SessionProtocol.H1;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
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
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.PathAndQuery;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.util.AsciiString;
import io.netty.util.NetUtil;

class HttpServerTest {

    private static final EventLoopGroup workerGroup = EventLoopGroups.newEventLoopGroup(1);

    private static final ClientFactory clientFactory =
            ClientFactory.builder()
                         .workerGroup(workerGroup, false) // Will be shut down by the Server.
                         .idleTimeout(Duration.ofSeconds(3))
                         .tlsNoVerify()
                         .build();

    private static final long MAX_CONTENT_LENGTH = 65536;

    private static final AtomicInteger pendingRequestLogs = new AtomicInteger();
    private static final BlockingQueue<RequestLog> requestLogs = new LinkedBlockingQueue<>();
    private static volatile long serverRequestTimeoutMillis;
    private static volatile long serverMaxRequestLength;
    private static volatile long clientWriteTimeoutMillis;
    private static volatile long clientResponseTimeoutMillis;
    private static volatile long clientMaxResponseLength;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            sb.workerGroup(workerGroup, true);
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            sb.service("/delay/{delay}", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final long delayMillis = Long.parseLong(ctx.pathParam("delay"));
                    final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
                    final HttpResponse res = HttpResponse.from(responseFuture);
                    ctx.eventLoop().schedule(() -> responseFuture.complete(HttpResponse.of(HttpStatus.OK)),
                                             delayMillis, TimeUnit.MILLISECONDS);
                    return res;
                }
            });

            sb.service("/delay-deferred/{delay}", (ctx, req) -> {
                final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
                final HttpResponse res = HttpResponse.from(responseFuture);
                final long delayMillis = Long.parseLong(ctx.pathParam("delay"));
                ctx.eventLoop().schedule(() -> responseFuture.complete(HttpResponse.of(HttpStatus.OK)),
                                         delayMillis, TimeUnit.MILLISECONDS);
                return res;
            });

            sb.service("/delay-custom/{delay}", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
                    final HttpResponse res = HttpResponse.from(responseFuture);
                    ctx.whenRequestCancelling().thenRun(
                            () -> responseFuture.complete(
                                    HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "timed out")));
                    final long delayMillis = Long.parseLong(ctx.pathParam("delay"));
                    ctx.eventLoop().schedule(() -> responseFuture.complete(HttpResponse.of(HttpStatus.OK)),
                                             delayMillis, TimeUnit.MILLISECONDS);
                    return res;
                }
            });

            sb.service("/delay-custom-deferred/{delay}", (ctx, req) -> {
                final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
                final HttpResponse res = HttpResponse.from(responseFuture);
                ctx.whenRequestCancelling().thenRun(
                        () -> responseFuture.complete(HttpResponse.of(
                                HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "timed out")));
                final long delayMillis = Long.parseLong(ctx.pathParam("delay"));
                ctx.eventLoop().schedule(() -> responseFuture.complete(HttpResponse.of(HttpStatus.OK)),
                                         delayMillis, TimeUnit.MILLISECONDS);
                return res;
            });

            sb.service("/informed_delay/{delay}", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final long delayMillis = Long.parseLong(ctx.pathParam("delay"));
                    final HttpResponseWriter res = HttpResponse.streaming();

                    // Send 9 informational responses before sending the actual response.
                    for (int i = 1; i <= 9; i++) {
                        ctx.eventLoop().schedule(
                                () -> res.write(ResponseHeaders.of(HttpStatus.PROCESSING)),
                                delayMillis * i / 10, TimeUnit.MILLISECONDS);
                    }

                    // Send the actual response.
                    ctx.eventLoop().schedule(
                            () -> {
                                res.write(ResponseHeaders.of(HttpStatus.OK));
                                res.close();
                            },
                            delayMillis, TimeUnit.MILLISECONDS);
                    return res;
                }
            });

            sb.service("/content_delay/{delay}", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final long delayMillis = Long.parseLong(ctx.pathParam("delay"));
                    final boolean pooled = "pooled".equals(ctx.query());

                    final HttpResponseWriter res = HttpResponse.streaming();
                    res.write(ResponseHeaders.of(HttpStatus.OK));

                    // Send 10 characters ('0' - '9') at fixed rate.
                    for (int i = 0; i < 10; i++) {
                        final int finalI = i;
                        ctx.eventLoop().schedule(
                                () -> {
                                    final HttpData data;
                                    if (pooled) {
                                        final ByteBuf content = PooledByteBufAllocator.DEFAULT
                                                .buffer(1)
                                                .writeByte('0' + finalI);
                                        data = HttpData.wrap(content);
                                    } else {
                                        data = HttpData.ofAscii(String.valueOf(finalI));
                                    }
                                    res.write(data);
                                    if (finalI == 9) {
                                        res.close();
                                    }
                                },
                                delayMillis * i / 10, TimeUnit.MILLISECONDS);
                    }
                    return res;
                }
            });

            sb.serviceUnder("/path", new AbstractHttpService() {
                @Override
                protected HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK,
                                                              HttpHeaderNames.CONTENT_LENGTH,
                                                              ctx.mappedPath().length()));
                }

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK,
                                               HttpHeaderNames.CONTENT_LENGTH,
                                               ctx.mappedPath().length()),
                            HttpData.ofAscii(ctx.mappedPath()));
                }
            });

            sb.service("/echo", new AbstractHttpService() {
                @Override
                protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
                    final HttpResponseWriter res = HttpResponse.streaming();
                    res.write(ResponseHeaders.of(HttpStatus.OK));
                    req.subscribe(new Subscriber<HttpObject>() {
                        private Subscription subscription;

                        @Override
                        public void onSubscribe(Subscription subscription) {
                            this.subscription = subscription;
                            subscription.request(1);
                        }

                        @Override
                        public void onNext(HttpObject http2Object) {
                            if (http2Object instanceof HttpData) {
                                res.write(http2Object);
                            }
                            subscription.request(1);
                        }

                        @Override
                        public void onError(Throwable t) {
                            res.close(t);
                        }

                        @Override
                        public void onComplete() {
                            res.close();
                        }
                    });
                    return res;
                }
            });

            sb.service("/strings", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK,
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8),
                            HttpData.ofUtf8("Armeria "),
                            HttpData.ofUtf8("is "),
                            HttpData.ofUtf8("awesome!"));
                }
            }.decorate(EncodingService.newDecorator()));

            sb.service("/images", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK,
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.PNG),
                            HttpData.ofUtf8("Armeria "),
                            HttpData.ofUtf8("is "),
                            HttpData.ofUtf8("awesome!"));
                }
            }.decorate(EncodingService.newDecorator()));

            sb.service("/small", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final String response = Strings.repeat("a", 1023);
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK,
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                               HttpHeaderNames.CONTENT_LENGTH, response.length()),
                            HttpData.ofUtf8(response));
                }
            }.decorate(EncodingService.newDecorator()));

            sb.service("/large", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final String response = Strings.repeat("a", 1024);
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK,
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                               HttpHeaderNames.CONTENT_LENGTH, response.length()),
                            HttpData.ofUtf8(response));
                }
            }.decorate(EncodingService.newDecorator()));

            sb.service("/sslsession", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    if (ctx.sessionProtocol().isTls()) {
                        assertThat(ctx.sslSession()).isNotNull();
                    } else {
                        assertThat(ctx.sslSession()).isNull();
                    }
                    return HttpResponse.of(HttpStatus.OK);
                }
            }.decorate(EncodingService.newDecorator()));

            sb.service("/headers", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK,
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                               HttpHeaderNames.of("x-custom-header1"), "custom1",
                                               HttpHeaderNames.of("X-Custom-Header2"), "custom2"),
                            HttpData.ofUtf8("headers"));
                }
            }.decorate(EncodingService.newDecorator()));

            sb.service("/trailers", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK),
                            HttpData.ofAscii("trailers incoming!"),
                            HttpHeaders.of(HttpHeaderNames.of("foo"), "bar"));
                }
            });

            sb.service("/head-headers-only", (ctx, req) -> HttpResponse.of(HttpStatus.OK));

            sb.service("/additional-trailers-other-trailers", (ctx, req) -> {
                ctx.mutateAdditionalResponseTrailers(
                        mutator -> mutator.add(HttpHeaderNames.of("additional-trailer"), "value2"));
                return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                       HttpData.ofAscii("foobar"),
                                       HttpHeaders.of(HttpHeaderNames.of("original-trailer"), "value1"));
            });

            sb.service("/additional-trailers-no-other-trailers", (ctx, req) -> {
                ctx.mutateAdditionalResponseTrailers(
                        mutator -> mutator.add(HttpHeaderNames.of("additional-trailer"), "value2"));
                final String payload = "foobar";
                return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                       HttpData.ofUtf8(payload).withEndOfStream());
            });

            sb.service("/additional-trailers-no-eos", (ctx, req) -> {
                ctx.mutateAdditionalResponseTrailers(
                        mutator -> mutator.add(HttpHeaderNames.of("additional-trailer"), "value2"));
                final String payload = "foobar";
                return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                       HttpData.ofUtf8(payload).withEndOfStream());
            });

            sb.serviceUnder("/not-cached-paths", (ctx, req) -> HttpResponse.of(HttpStatus.OK));

            sb.serviceUnder("/cached-paths", new HttpService() {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Override
                public boolean shouldCachePath(String path, @Nullable String query, Route route) {
                    return true;
                }
            });

            sb.service("/cached-exact-path", (ctx, req) -> HttpResponse.of(HttpStatus.OK));

            final Function<? super HttpService, ? extends HttpService> decorator =
                    s -> new SimpleDecoratingHttpService(s) {
                        @Override
                        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                            pendingRequestLogs.incrementAndGet();
                            if (serverRequestTimeoutMillis == 0) {
                                ctx.clearRequestTimeout();
                            } else {
                                ctx.setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW,
                                                            serverRequestTimeoutMillis);
                            }
                            ctx.setMaxRequestLength(serverMaxRequestLength);
                            ctx.log().whenComplete().thenAccept(log -> {
                                pendingRequestLogs.decrementAndGet();
                                requestLogs.add(log);
                            });
                            return unwrap().serve(ctx, req);
                        }
                    };
            sb.decorator(decorator);

            sb.maxRequestLength(MAX_CONTENT_LENGTH);
            sb.idleTimeout(Duration.ofSeconds(5));

            sb.disableServerHeader();
            sb.disableDateHeader();
        }
    };

    @AfterAll
    static void destroy() {
        clientFactory.closeAsync();
    }

    @BeforeEach
    void resetOptions() {
        serverRequestTimeoutMillis = 10000L;
        clientWriteTimeoutMillis = 3000L;
        clientResponseTimeoutMillis = 10000L;

        serverMaxRequestLength = MAX_CONTENT_LENGTH;
        clientMaxResponseLength = MAX_CONTENT_LENGTH;

        PathAndQuery.clearCachedPaths();
    }

    @AfterEach
    void clearRequestLogs() {
        try {
            await().until(() -> pendingRequestLogs.get() == 0);
        } finally {
            pendingRequestLogs.set(0);
            requestLogs.clear();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testGet(WebClient client) throws Exception {
        final AggregatedHttpResponse res = client.get("/path/foo").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("/foo");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testHead(WebClient client) throws Exception {
        final AggregatedHttpResponse res = client.head("/path/blah").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.content().isEmpty()).isTrue();
        assertThat(res.headers().getInt(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo(5);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testPost(WebClient client) throws Exception {
        final AggregatedHttpResponse res = client.post("/echo", "foo").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("foo");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testTimeout(WebClient client) throws Exception {
        serverRequestTimeoutMillis = 100L;
        final AggregatedHttpResponse res = client.get("/delay/2000").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("503 Service Unavailable");
        assertThat(requestLogs.take().responseHeaders().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testTimeout_deferred(WebClient client) throws Exception {
        serverRequestTimeoutMillis = 100L;
        final AggregatedHttpResponse res = client.get("/delay-deferred/2000").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("503 Service Unavailable");
        assertThat(requestLogs.take().responseHeaders().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testTimeout_customHandler(WebClient client) throws Exception {
        serverRequestTimeoutMillis = 100L;
        final AggregatedHttpResponse res = client.get("/delay-custom/2000").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("timed out");
        assertThat(requestLogs.take().responseHeaders().status()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testTimeout_customHandler_deferred(WebClient client) throws Exception {
        serverRequestTimeoutMillis = 100L;
        final AggregatedHttpResponse res = client.get("/delay-custom-deferred/2000").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("timed out");
        assertThat(requestLogs.take().responseHeaders().status()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testTimeoutAfterInformationals(WebClient client) throws Exception {
        serverRequestTimeoutMillis = 1000L;
        final AggregatedHttpResponse res = client.get("/informed_delay/2000").aggregate().get();
        assertThat(res.informationals()).isNotEmpty();
        res.informationals().forEach(h -> {
            assertThat(h.status()).isEqualTo(HttpStatus.PROCESSING);
            assertThat(h.names()).contains(HttpHeaderNames.STATUS);
        });

        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo("503 Service Unavailable");
        assertThat(requestLogs.take().responseHeaders().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testTimeoutAfterPartialContent(WebClient client) throws Exception {
        serverRequestTimeoutMillis = 1000L;
        final CompletableFuture<AggregatedHttpResponse> f = client.get("/content_delay/2000").aggregate();

        // Because the service has written out the content partially, there's no way for the service
        // to reply with '503 Service Unavailable', so it will just close the stream.

        final Class<? extends Throwable> expectedCauseType =
                client.scheme().sessionProtocol().isMultiplex() ?
                ClosedStreamException.class : ClosedSessionException.class;

        assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class)
                                  .hasCauseInstanceOf(expectedCauseType);
    }

    /**
     * Similar to {@link #testTimeoutAfterPartialContent(WebClient)}, but tests the case where the service
     * produces a pooled buffers.
     */
    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testTimeoutAfterPartialContentWithPooling(WebClient client) throws Exception {
        serverRequestTimeoutMillis = 1000L;
        final CompletableFuture<AggregatedHttpResponse> f =
                client.get("/content_delay/2000?pooled").aggregate();

        // Because the service has written out the content partially, there's no way for the service
        // to reply with '503 Service Unavailable', so it will just close the stream.

        final Class<? extends Throwable> expectedCauseType =
                client.scheme().sessionProtocol().isMultiplex() ?
                ClosedStreamException.class : ClosedSessionException.class;

        assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class)
                                  .hasCauseInstanceOf(expectedCauseType);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testTooLargeContentToNonExistentService(WebClient client) {
        final byte[] content = new byte[(int) MAX_CONTENT_LENGTH + 1];
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
            final AggregatedHttpResponse res = client.post("/non-existent", content).aggregate().join();
            assertThat(res.status()).isSameAs(HttpStatus.NOT_FOUND);
            assertThat(res.contentUtf8()).isEqualTo("404 Not Found");
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testStrings_noAcceptEncoding(WebClient client) throws Exception {
        final RequestHeaders req = RequestHeaders.of(HttpMethod.GET, "/strings");
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(req).aggregate();

        final AggregatedHttpResponse res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(res.headers().get(HttpHeaderNames.VARY)).isNull();
        assertThat(res.contentUtf8()).isEqualTo("Armeria is awesome!");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testStrings_acceptEncodingGzip(WebClient client) throws Exception {
        final RequestHeaders req = RequestHeaders.of(HttpMethod.GET, "/strings",
                                                     HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(req).aggregate();

        final AggregatedHttpResponse res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(res.headers().get(HttpHeaderNames.VARY)).isEqualTo("accept-encoding");

        final byte[] decoded;
        try (GZIPInputStream unzipper = new GZIPInputStream(
                new ByteArrayInputStream(res.content().array()))) {
            decoded = ByteStreams.toByteArray(unzipper);
        } catch (EOFException e) {
            throw new IllegalArgumentException(e);
        }
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo("Armeria is awesome!");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testStrings_acceptEncodingGzip_imageContentType(WebClient client) throws Exception {
        final RequestHeaders req = RequestHeaders.of(HttpMethod.GET, "/images",
                                                     HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(req).aggregate();

        final AggregatedHttpResponse res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(res.headers().get(HttpHeaderNames.VARY)).isNull();
        assertThat(res.contentUtf8()).isEqualTo("Armeria is awesome!");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testStrings_acceptEncodingGzip_smallFixedContent(WebClient client) throws Exception {
        final RequestHeaders req = RequestHeaders.of(HttpMethod.GET, "/small",
                                                     HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(req).aggregate();

        final AggregatedHttpResponse res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(res.headers().get(HttpHeaderNames.VARY)).isNull();
        assertThat(res.contentUtf8()).isEqualTo(Strings.repeat("a", 1023));
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testStrings_acceptEncodingGzip_largeFixedContent(WebClient client) throws Exception {
        final RequestHeaders req = RequestHeaders.of(HttpMethod.GET, "/large",
                                                     HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(req).aggregate();

        final AggregatedHttpResponse res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(res.headers().get(HttpHeaderNames.VARY)).isEqualTo("accept-encoding");

        final byte[] decoded;
        try (GZIPInputStream unzipper = new GZIPInputStream(
                new ByteArrayInputStream(res.content().array()))) {
            decoded = ByteStreams.toByteArray(unzipper);
        }
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo(Strings.repeat("a", 1024));
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testStrings_acceptEncodingDeflate(WebClient client) throws Exception {
        final RequestHeaders req = RequestHeaders.of(HttpMethod.GET, "/strings",
                                                     HttpHeaderNames.ACCEPT_ENCODING, "deflate");
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(req).aggregate();

        final AggregatedHttpResponse res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("deflate");
        assertThat(res.headers().get(HttpHeaderNames.VARY)).isEqualTo("accept-encoding");

        final byte[] decoded;
        try (InflaterInputStream unzipper =
                     new InflaterInputStream(new ByteArrayInputStream(res.content().array()))) {
            decoded = ByteStreams.toByteArray(unzipper);
        }
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo("Armeria is awesome!");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testStrings_acceptEncodingUnknown(WebClient client) throws Exception {
        final RequestHeaders req = RequestHeaders.of(HttpMethod.GET, "/strings",
                                                     HttpHeaderNames.ACCEPT_ENCODING, "piedpiper");
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(req).aggregate();

        final AggregatedHttpResponse res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(res.headers().get(HttpHeaderNames.VARY)).isNull();
        assertThat(res.contentUtf8()).isEqualTo("Armeria is awesome!");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testSslSession(WebClient client) throws Exception {
        final CompletableFuture<AggregatedHttpResponse> f = client.get("/sslsession").aggregate();

        final AggregatedHttpResponse res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testHeadHeadersOnly(WebClient client, SessionProtocol protocol) throws Exception {
        assumeThat(protocol).isSameAs(H1C);

        final int port = server.httpPort();
        try (Socket s = new Socket(NetUtil.LOCALHOST, port)) {
            s.setSoTimeout(10000);
            final InputStream in = s.getInputStream();
            final OutputStream out = s.getOutputStream();
            out.write("HEAD /head-headers-only HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

            // Should neither be chunked nor have content.
            assertThat(new String(ByteStreams.toByteArray(in)))
                    .isEqualTo("HTTP/1.1 200 OK\r\n" +
                               "content-type: text/plain; charset=utf-8\r\n" +
                               "content-length: 6\r\n\r\n");
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testExpect100Continue(WebClient client) throws Exception {
        // Makes sure the server sends a '100 Continue' response if 'expect: 100-continue' header exists.
        final AggregatedHttpResponse res =
                client.execute(RequestHeaders.of(HttpMethod.POST, "/echo",
                                                 HttpHeaderNames.EXPECT, "100-continue"),
                               "met expectation")
                      .aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.informationals()).containsExactly(ResponseHeaders.of(100));
        assertThat(res.contentUtf8()).isEqualTo("met expectation");

        // Makes sure the server does not send a '100 Continue' response if 'expect: 100-continue' header
        // does not exists.
        final AggregatedHttpResponse res2 =
                client.execute(RequestHeaders.of(HttpMethod.POST, "/echo"), "without expectation")
                      .aggregate().join();

        assertThat(res2.status()).isEqualTo(HttpStatus.OK);
        assertThat(res2.informationals()).isEmpty();
        assertThat(res2.contentUtf8()).isEqualTo("without expectation");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testExpect100ContinueDoesNotBreakHttp1Decoder(WebClient client, SessionProtocol protocol)
            throws Exception {
        assumeThat(protocol).isSameAs(H1C);

        final int port = server.httpPort();
        try (Socket s = new Socket(NetUtil.LOCALHOST, port)) {
            s.setSoTimeout(10000);
            final InputStream in = s.getInputStream();
            final OutputStream out = s.getOutputStream();
            // Send 4 pipelined requests with 'Expect: 100-continue' header.
            out.write((Strings.repeat("POST /head-headers-only HTTP/1.1\r\n" +
                                      "Expect: 100-continue\r\n" +
                                      "Content-Length: 0\r\n\r\n", 3) +
                       "POST /head-headers-only HTTP/1.1\r\n" +
                       "Expect: 100-continue\r\n" +
                       "Content-Length: 0\r\n" +
                       "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));

            // '100 Continue' responses must appear once for each '200 OK' response.
            assertThat(new String(ByteStreams.toByteArray(in)))
                    .isEqualTo(Strings.repeat("HTTP/1.1 100 Continue\r\n\r\n" +
                                              "HTTP/1.1 200 OK\r\n" +
                                              "content-type: text/plain; charset=utf-8\r\n" +
                                              "content-length: 6\r\n\r\n200 OK", 4));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testStreamRequestLongerThanTimeout(WebClient client) throws Exception {
        // Disable timeouts and length limits so that test does not fail due to slow transfer.
        clientWriteTimeoutMillis = 0;
        clientResponseTimeoutMillis = 0;
        clientMaxResponseLength = 0;
        serverRequestTimeoutMillis = 0;

        final HttpRequestWriter request = HttpRequest.streaming(HttpMethod.POST, "/echo");
        final HttpResponse response = client.execute(request);
        request.write(HttpData.ofUtf8("a"));
        Thread.sleep(2000);
        request.write(HttpData.ofUtf8("b"));
        Thread.sleep(2000);
        request.write(HttpData.ofUtf8("c"));
        Thread.sleep(2000);
        request.write(HttpData.ofUtf8("d"));
        Thread.sleep(2000);
        request.write(HttpData.ofUtf8("e"));
        request.close();
        assertThat(response.aggregate().get().contentUtf8()).isEqualTo("abcde");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testHeaders(WebClient client) throws Exception {
        final CompletableFuture<AggregatedHttpResponse> f = client.get("/headers").aggregate();

        final AggregatedHttpResponse res = f.get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        // Verify all header names are in lowercase
        for (AsciiString headerName : res.headers().names()) {
            headerName.chars().filter(Character::isAlphabetic)
                      .forEach(c -> assertThat(Character.isLowerCase(c)).isTrue());
        }

        assertThat(res.headers().get(HttpHeaderNames.of("x-custom-header1"))).isEqualTo("custom1");
        assertThat(res.headers().get(HttpHeaderNames.of("x-custom-header2"))).isEqualTo("custom2");
        assertThat(res.contentUtf8()).isEqualTo("headers");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testTrailers(WebClient client) throws Exception {
        final CompletableFuture<AggregatedHttpResponse> f = client.get("/trailers").aggregate();

        final AggregatedHttpResponse res = f.get();
        assertThat(res.trailers().get(HttpHeaderNames.of("foo"))).isEqualTo("bar");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testExactPathCached(WebClient client) throws Exception {
        assertThat(client.get("/cached-exact-path")
                         .aggregate().get().status()).isEqualTo(HttpStatus.OK);
        assertThat(PathAndQuery.cachedPaths()).contains("/cached-exact-path");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testPrefixPathNotCached(WebClient client) throws Exception {
        assertThat(client.get("/not-cached-paths/hoge")
                         .aggregate().get().status()).isEqualTo(HttpStatus.OK);
        assertThat(PathAndQuery.cachedPaths()).doesNotContain("/not-cached-paths/hoge");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testPrefixPath_cacheForced(WebClient client) throws Exception {
        assertThat(client.get("/cached-paths/hoge")
                         .aggregate().get().status()).isEqualTo(HttpStatus.OK);
        assertThat(PathAndQuery.cachedPaths()).contains("/cached-paths/hoge");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testAdditionalTrailersOtherTrailers(WebClient client, SessionProtocol protocol) {
        assumeThat(protocol.isMultiplex()).isTrue();

        final HttpHeaders trailers = client.get("/additional-trailers-other-trailers")
                                           .aggregate().join().trailers();
        assertThat(trailers.get(HttpHeaderNames.of("original-trailer"))).isEqualTo("value1");
        assertThat(trailers.get(HttpHeaderNames.of("additional-trailer"))).isEqualTo("value2");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testAdditionalTrailersNoEndOfStream(WebClient client, SessionProtocol protocol) {
        assumeThat(protocol.isMultiplex()).isTrue();

        final HttpHeaders trailers = client.get("/additional-trailers-no-eos")
                                           .aggregate().join().trailers();
        assertThat(trailers.get(HttpHeaderNames.of("additional-trailer"))).isEqualTo("value2");
    }

    @ParameterizedTest
    @ArgumentsSource(ClientAndProtocolProvider.class)
    void testAdditionalTrailersNoOtherTrailers(WebClient client, SessionProtocol protocol) {
        assumeThat(protocol.isMultiplex()).isTrue();

        final HttpHeaders trailers = client.get("/additional-trailers-no-other-trailers")
                                           .aggregate().join().trailers();
        assertThat(trailers.get(HttpHeaderNames.of("additional-trailer"))).isEqualTo("value2");
    }

    private static class ClientAndProtocolProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(H1C, H1, H2C, H2)
                         .map(protocol -> {
                             final WebClientBuilder builder = WebClient.builder(
                                     protocol.uriText() + "://127.0.0.1:" +
                                     (protocol.isTls() ? server.httpsPort() : server.httpPort()));

                             builder.factory(clientFactory);
                             builder.decorator(
                                     (delegate, ctx, req) -> {
                                         ctx.setWriteTimeoutMillis(clientWriteTimeoutMillis);
                                         if (clientResponseTimeoutMillis == 0) {
                                             ctx.clearResponseTimeout();
                                         } else {
                                             ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW,
                                                                          clientResponseTimeoutMillis);
                                         }
                                         ctx.setMaxResponseLength(clientMaxResponseLength);
                                         return delegate.execute(ctx, req);
                                     });

                             return Arguments.of(builder.build(), protocol);
                         });
        }
    }
}
