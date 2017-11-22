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
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.junit.After;
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

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.DefaultHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.internal.ByteBufHttpData;
import com.linecorp.armeria.internal.InboundTrafficController;
import com.linecorp.armeria.server.encoding.HttpEncodingService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AsciiString;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetector.Level;
import io.netty.util.concurrent.GlobalEventExecutor;

@RunWith(Parameterized.class)
public class HttpServerTest {
    private static final Logger logger = LoggerFactory.getLogger(HttpServerTest.class);

    private static final EventLoopGroup workerGroup = EventLoopGroups.newEventLoopGroup(1);

    private static final ClientFactory clientFactory = new ClientFactoryBuilder()
            .workerGroup(workerGroup, false) // Will be shut down by the Server.
            .idleTimeout(Duration.ofSeconds(3))
            .sslContextCustomizer(b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE))
            .build();

    private static final long MAX_CONTENT_LENGTH = 65536;
    // Stream as much as twice of the heap. Stream less to avoid OOME when leak detection is enabled.
    private static final long STREAMING_CONTENT_LENGTH =
            Runtime.getRuntime().maxMemory() * 2 / (ResourceLeakDetector.getLevel() == Level.PARANOID ? 8 : 1);
    private static final int STREAMING_CONTENT_CHUNK_LENGTH =
            (int) Math.min(Integer.MAX_VALUE, STREAMING_CONTENT_LENGTH / 8);

    @Parameters(name = "{index}: {0}")
    public static Collection<SessionProtocol> parameters() {
        return ImmutableList.of(H1C, H1, H2C, H2);
    }

    private static final AtomicInteger pendingRequestLogs = new AtomicInteger();
    private static final BlockingQueue<RequestLog> requestLogs = new LinkedBlockingQueue<>();
    private static volatile long serverRequestTimeoutMillis;
    private static volatile long serverMaxRequestLength;
    private static volatile long clientWriteTimeoutMillis;
    private static volatile long clientResponseTimeoutMillis;
    private static volatile long clientMaxResponseLength;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            sb.workerGroup(workerGroup, true);
            sb.port(0, HTTP);
            sb.port(0, HTTPS);

            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sb.sslContext(HTTPS, ssc.certificate(), ssc.privateKey());

            sb.service("/delay/{delay}", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    final long delayMillis = Long.parseLong(ctx.pathParam("delay"));
                    ctx.eventLoop().schedule(() -> res.respond(HttpStatus.OK),
                                             delayMillis, TimeUnit.MILLISECONDS);
                }
            });

            sb.service("/delay-custom/{delay}", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    ctx.setRequestTimeoutHandler(
                            () -> res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "timed out"));
                    final long delayMillis = Long.parseLong(ctx.pathParam("delay"));
                    ctx.eventLoop().schedule(() -> res.respond(HttpStatus.OK),
                                             delayMillis, TimeUnit.MILLISECONDS);
                }
            });

            sb.service("/informed_delay/{delay}", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    final long delayMillis = Long.parseLong(ctx.pathParam("delay"));

                    // Send 9 informational responses before sending the actual response.
                    for (int i = 1; i <= 9; i++) {
                        ctx.eventLoop().schedule(
                                () -> res.respond(HttpStatus.PROCESSING),
                                delayMillis * i / 10, TimeUnit.MILLISECONDS);
                    }

                    // Send the actual response.
                    ctx.eventLoop().schedule(
                            () -> res.respond(HttpStatus.OK),
                            delayMillis, TimeUnit.MILLISECONDS);
                }
            });

            sb.service("/content_delay/{delay}", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    final long delayMillis = Long.parseLong(ctx.pathParam("delay"));
                    final boolean pooled = "pooled".equals(ctx.query());

                    res.write(HttpHeaders.of(HttpStatus.OK));

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
                                        data = new ByteBufHttpData(content, false);
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
                }
            });

            sb.serviceUnder("/path", new AbstractHttpService() {
                @Override
                protected void doHead(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.write(HttpHeaders.of(HttpStatus.OK)
                                         .setInt(HttpHeaderNames.CONTENT_LENGTH,
                                                 ctx.mappedPath().length()));
                    res.close();
                }

                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.write(HttpHeaders.of(HttpStatus.OK)
                                         .setInt(HttpHeaderNames.CONTENT_LENGTH,
                                                 ctx.mappedPath().length()));
                    res.write(HttpData.ofAscii(ctx.mappedPath()));
                    res.close();
                }
            });

            sb.service("/echo", new AbstractHttpService() {
                @Override
                protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.write(HttpHeaders.of(HttpStatus.OK));
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
                }
            });

            sb.service("/count", new CountingService(false));
            sb.service("/slow_count", new CountingService(true));

            sb.serviceUnder("/zeroes", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    final long length = Long.parseLong(ctx.mappedPath().substring(1));
                    res.write(HttpHeaders.of(HttpStatus.OK)
                                         .setLong(HttpHeaderNames.CONTENT_LENGTH, length));

                    stream(res, length, STREAMING_CONTENT_CHUNK_LENGTH);
                }
            });

            sb.service("/strings", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.write(HttpHeaders.of(HttpStatus.OK).set(HttpHeaderNames.CONTENT_TYPE, "text/plain"));

                    res.write(HttpData.ofUtf8("Armeria "));
                    res.write(HttpData.ofUtf8("is "));
                    res.write(HttpData.ofUtf8("awesome!"));
                    res.close();
                }
            }.decorate(HttpEncodingService.class));

            sb.service("/images", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.write(HttpHeaders.of(HttpStatus.OK).set(HttpHeaderNames.CONTENT_TYPE, "image/png"));

                    res.write(HttpData.ofUtf8("Armeria "));
                    res.write(HttpData.ofUtf8("is "));
                    res.write(HttpData.ofUtf8("awesome!"));
                    res.close();
                }
            }.decorate(HttpEncodingService.class));

            sb.service("/small", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    String response = Strings.repeat("a", 1023);
                    res.write(HttpHeaders.of(HttpStatus.OK)
                                         .set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                                         .setInt(HttpHeaderNames.CONTENT_LENGTH, response.length()));
                    res.write(HttpData.ofUtf8(response));
                    res.close();
                }
            }.decorate(HttpEncodingService.class));

            sb.service("/large", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    String response = Strings.repeat("a", 1024);
                    res.write(HttpHeaders.of(HttpStatus.OK)
                                         .set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                                         .setInt(HttpHeaderNames.CONTENT_LENGTH, response.length()));
                    res.write(HttpData.ofUtf8(response));
                    res.close();
                }
            }.decorate(HttpEncodingService.class));

            sb.service("/sslsession", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    if (ctx.sessionProtocol().isTls()) {
                        assertNotNull(ctx.sslSession());
                    } else {
                        assertNull(ctx.sslSession());
                    }
                    res.respond(HttpStatus.OK);
                }
            }.decorate(HttpEncodingService.class));

            sb.service("/headers", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                    res.write(HttpHeaders.of(HttpStatus.OK).set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                                         .add(AsciiString.of("x-custom-header1"), "custom1")
                                         .add(AsciiString.of("X-Custom-Header2"), "custom2"));

                    res.write(HttpData.ofUtf8("headers"));
                    res.close();
                }
            }.decorate(HttpEncodingService.class));

            sb.service("/trailers", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
                        throws Exception {
                    res.write(HttpHeaders.of(HttpStatus.OK));
                    res.write(HttpData.ofAscii("trailers incoming!"));
                    res.write(HttpHeaders.of(AsciiString.of("foo"), "bar"));
                    res.close();
                }
            });

            final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator =
                    s -> new SimpleDecoratingService<HttpRequest, HttpResponse>(s) {
                        @Override
                        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                            pendingRequestLogs.incrementAndGet();
                            ctx.setRequestTimeoutMillis(serverRequestTimeoutMillis);
                            ctx.setMaxRequestLength(serverMaxRequestLength);
                            ctx.log().addListener(log -> {
                                pendingRequestLogs.decrementAndGet();
                                requestLogs.add(log);
                            }, RequestLogAvailability.COMPLETE);
                            return delegate().serve(ctx, req);
                        }
                    };
            sb.decorator(decorator);

            sb.defaultMaxRequestLength(MAX_CONTENT_LENGTH);
            sb.idleTimeout(Duration.ofSeconds(5));
        }
    };

    private final SessionProtocol protocol;
    private HttpClient client;

    public HttpServerTest(SessionProtocol protocol) {
        this.protocol = protocol;
    }

    @AfterClass
    public static void destroy() {
        CompletableFuture.runAsync(clientFactory::close);
    }

    @Before
    public void resetOptions() {
        serverRequestTimeoutMillis = 10000L;
        clientWriteTimeoutMillis = 3000L;
        clientResponseTimeoutMillis = 10000L;

        serverMaxRequestLength = MAX_CONTENT_LENGTH;
        clientMaxResponseLength = MAX_CONTENT_LENGTH;
    }

    @After
    public void clearRequestLogs() {
        try {
            await().until(() -> pendingRequestLogs.get() == 0);
        } finally {
            pendingRequestLogs.set(0);
            requestLogs.clear();
        }
    }

    @Test(timeout = 10000)
    public void testGet() throws Exception {
        final AggregatedHttpMessage res = client().get("/path/foo").aggregate().get();
        assertThat(res.headers().status(), is(HttpStatus.OK));
        assertThat(res.content().toStringUtf8(), is("/foo"));
    }

    @Test(timeout = 10000)
    public void testHead() throws Exception {
        final AggregatedHttpMessage res = client().head("/path/blah").aggregate().get();
        assertThat(res.headers().status(), is(HttpStatus.OK));
        assertThat(res.content().isEmpty(), is(true));
        assertThat(res.headers().getInt(HttpHeaderNames.CONTENT_LENGTH), is(5));
    }

    @Test(timeout = 10000)
    public void testPost() throws Exception {
        final AggregatedHttpMessage res = client().post("/echo", "foo").aggregate().get();
        assertThat(res.headers().status(), is(HttpStatus.OK));
        assertThat(res.content().toStringUtf8(), is("foo"));
    }

    @Test(timeout = 10000)
    public void testTimeout() throws Exception {
        serverRequestTimeoutMillis = 100L;
        final AggregatedHttpMessage res = client().get("/delay/2000").aggregate().get();
        assertThat(res.headers().status(), is(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE), is(MediaType.PLAIN_TEXT_UTF_8.toString()));
        assertThat(res.content().toStringUtf8(), is("503 Service Unavailable"));
        assertThat(requestLogs.take().statusCode(), is(503));
    }

    @Test(timeout = 10000)
    public void testTimeout_customHandler() throws Exception {
        serverRequestTimeoutMillis = 100L;
        final AggregatedHttpMessage res = client().get("/delay-custom/2000").aggregate().get();
        assertThat(res.headers().status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE), is(MediaType.PLAIN_TEXT_UTF_8.toString()));
        assertThat(res.content().toStringUtf8(), is("timed out"));
        assertThat(requestLogs.take().statusCode(), is(200));
    }

    @Test(timeout = 10000)
    public void testTimeoutAfterInformationals() throws Exception {
        serverRequestTimeoutMillis = 1000L;
        final AggregatedHttpMessage res = client().get("/informed_delay/2000").aggregate().get();
        assertThat(res.informationals(), is(not(empty())));
        res.informationals().forEach(h -> {
            assertThat(h.status(), is(HttpStatus.PROCESSING));
            assertThat(h.names(), contains(HttpHeaderNames.STATUS));
        });

        assertThat(res.headers().status(), is(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE), is(MediaType.PLAIN_TEXT_UTF_8.toString()));
        assertThat(res.content().toStringUtf8(), is("503 Service Unavailable"));
        assertThat(requestLogs.take().statusCode(), is(503));
    }

    @Test(timeout = 10000)
    public void testTimeoutAfterPartialContent() throws Exception {
        serverRequestTimeoutMillis = 1000L;
        CompletableFuture<AggregatedHttpMessage> f = client().get("/content_delay/2000").aggregate();

        // Because the service has written out the content partially, there's no way for the service
        // to reply with '503 Service Unavailable', so it will just close the stream.
        try {
            f.get();
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), is(instanceOf(ClosedSessionException.class)));
        }
    }

    /**
     * Similar to {@link #testTimeoutAfterPartialContent()}, but tests the case where the service produces
     * a pooled buffers.
     */
    @Test(timeout = 10000)
    public void testTimeoutAfterPartialContentWithPooling() throws Exception {
        serverRequestTimeoutMillis = 1000L;
        CompletableFuture<AggregatedHttpMessage> f = client().get("/content_delay/2000?pooled").aggregate();

        // Because the service has written out the content partially, there's no way for the service
        // to reply with '503 Service Unavailable', so it will just close the stream.
        try {
            f.get();
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), is(instanceOf(ClosedSessionException.class)));
        }
    }

    @Test(timeout = 10000)
    public void testTooLargeContent() throws Exception {
        clientWriteTimeoutMillis = 0L;
        final DefaultHttpRequest req = new DefaultHttpRequest(HttpMethod.POST, "/count");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        stream(req, MAX_CONTENT_LENGTH + 1, 1024);

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.REQUEST_ENTITY_TOO_LARGE));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE), is(MediaType.PLAIN_TEXT_UTF_8.toString()));
        assertThat(res.content().toStringUtf8(), is("413 Request Entity Too Large"));
    }

    @Test(timeout = 10000)
    public void testTooLargeContentToNonExistentService() throws Exception {
        final byte[] content = new byte[(int) MAX_CONTENT_LENGTH + 1];
        final AggregatedHttpMessage res = client().post("/non-existent", content).aggregate().get();
        assertThat(res.headers().status(), is(HttpStatus.NOT_FOUND));
        assertThat(res.content().toStringUtf8(), is("404 Not Found"));
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
        assertThat(InboundTrafficController.numDeferredReads(), is(greaterThan(oldNumDeferredReads)));
    }

    @Test(timeout = 10000)
    public void testStrings_noAcceptEncoding() throws Exception {
        final HttpHeaders req = HttpHeaders.of(HttpMethod.GET, "/strings");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is(nullValue()));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is(nullValue()));
        assertThat(res.content().toStringUtf8(), is("Armeria is awesome!"));
    }

    @Test(timeout = 10000)
    public void testStrings_acceptEncodingGzip() throws Exception {
        final HttpHeaders req = HttpHeaders.of(HttpMethod.GET, "/strings")
                                           .set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is("gzip"));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is("accept-encoding"));

        byte[] decoded;
        try (GZIPInputStream unzipper = new GZIPInputStream(new ByteArrayInputStream(res.content().array()))) {
            decoded = ByteStreams.toByteArray(unzipper);
        } catch (EOFException e) {
            throw new IllegalArgumentException(e);
        }
        assertThat(new String(decoded, StandardCharsets.UTF_8), is("Armeria is awesome!"));
    }

    @Test(timeout = 10000)
    public void testStrings_acceptEncodingGzip_imageContentType() throws Exception {
        final HttpHeaders req = HttpHeaders.of(HttpMethod.GET, "/images")
                                           .set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is(nullValue()));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is(nullValue()));
        assertThat(res.content().toStringUtf8(), is("Armeria is awesome!"));
    }

    @Test(timeout = 10000)
    public void testStrings_acceptEncodingGzip_smallFixedContent() throws Exception {
        final HttpHeaders req = HttpHeaders.of(HttpMethod.GET, "/small")
                                           .set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is(nullValue()));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is(nullValue()));
        assertThat(res.content().toStringUtf8(), is(Strings.repeat("a", 1023)));
    }

    @Test(timeout = 10000)
    public void testStrings_acceptEncodingGzip_largeFixedContent() throws Exception {
        final HttpHeaders req = HttpHeaders.of(HttpMethod.GET, "/large")
                                           .set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is("gzip"));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is("accept-encoding"));

        byte[] decoded;
        try (GZIPInputStream unzipper = new GZIPInputStream(new ByteArrayInputStream(res.content().array()))) {
            decoded = ByteStreams.toByteArray(unzipper);
        }
        assertThat(new String(decoded, StandardCharsets.UTF_8), is(Strings.repeat("a", 1024)));
    }

    @Test(timeout = 10000)
    public void testStrings_acceptEncodingDeflate() throws Exception {
        final HttpHeaders req = HttpHeaders.of(HttpMethod.GET, "/strings")
                                           .set(HttpHeaderNames.ACCEPT_ENCODING, "deflate");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is("deflate"));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is("accept-encoding"));

        byte[] decoded;
        try (InflaterInputStream unzipper =
                     new InflaterInputStream(new ByteArrayInputStream(res.content().array()))) {
            decoded = ByteStreams.toByteArray(unzipper);
        }
        assertThat(new String(decoded, StandardCharsets.UTF_8), is("Armeria is awesome!"));
    }

    @Test(timeout = 10000)
    public void testStrings_acceptEncodingUnknown() throws Exception {
        final HttpHeaders req = HttpHeaders.of(HttpMethod.GET, "/strings")
                                           .set(HttpHeaderNames.ACCEPT_ENCODING, "piedpiper");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is(nullValue()));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is(nullValue()));
        assertThat(res.content().toStringUtf8(), is("Armeria is awesome!"));
    }

    @Test(timeout = 10000)
    public void testSslSession() throws Exception {
        final CompletableFuture<AggregatedHttpMessage> f = client().get("/sslsession").aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
    }

    private void runStreamingRequestTest(String path) throws InterruptedException, ExecutionException {
        // Disable timeouts and length limits so that test does not fail due to slow transfer.
        clientWriteTimeoutMillis = 0;
        clientResponseTimeoutMillis = 0;
        serverRequestTimeoutMillis = 0;
        serverMaxRequestLength = 0;

        final DefaultHttpRequest req = new DefaultHttpRequest(HttpMethod.POST, path);
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        // Stream a large of the max memory.
        // This test will fail if the implementation keep the whole content in memory.
        final long expectedContentLength = STREAMING_CONTENT_LENGTH;

        try {
            stream(req, expectedContentLength, STREAMING_CONTENT_CHUNK_LENGTH);

            final AggregatedHttpMessage res = f.get();

            assertThat(res.status(), is(HttpStatus.OK));
            assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE),
                       is(MediaType.PLAIN_TEXT_UTF_8.toString()));
            assertThat(res.content().toStringUtf8(), is(String.valueOf(expectedContentLength)));
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
        assertThat(InboundTrafficController.numDeferredReads(), is(greaterThan(oldNumDeferredReads)));
    }

    @Test(timeout = 30000)
    public void testStreamRequestLongerThanTimeout() throws Exception {
        // Disable timeouts and length limits so that test does not fail due to slow transfer.
        clientWriteTimeoutMillis = 0;
        clientResponseTimeoutMillis = 0;
        clientMaxResponseLength = 0;
        serverRequestTimeoutMillis = 0;

        DefaultHttpRequest request = new DefaultHttpRequest(HttpMethod.POST, "/echo");
        HttpResponse response = client().execute(request);
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
        assertThat(response.aggregate().get().content().toStringUtf8(), is("abcde"));
    }

    private void runStreamingResponseTest(boolean slowClient) throws InterruptedException, ExecutionException {
        // Disable timeouts and length limits so that test does not fail due to slow transfer.
        clientWriteTimeoutMillis = 0;
        clientResponseTimeoutMillis = 0;
        clientMaxResponseLength = 0;
        serverRequestTimeoutMillis = 0;

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
        assertThat(status.get(), is(HttpStatus.OK));
        assertThat(consumer.numReceivedBytes(), is(STREAMING_CONTENT_LENGTH));
    }

    @Test(timeout = 10000)
    public void testHeaders() throws Exception {
        final HttpHeaders req = HttpHeaders.of(HttpMethod.GET, "/headers");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));

        // Verify all header names are in lowercase
        for (AsciiString headerName : res.headers().names()) {
            headerName.chars().filter(Character::isAlphabetic)
                      .forEach(c -> assertTrue(Character.isLowerCase(c)));
        }

        assertThat(res.headers().get(AsciiString.of("x-custom-header1")), is("custom1"));
        assertThat(res.headers().get(AsciiString.of("x-custom-header2")), is("custom2"));
        assertThat(res.content().toStringUtf8(), is("headers"));
    }

    @Test(timeout = 10000)
    public void testTrailers() throws Exception {
        final HttpHeaders req = HttpHeaders.of(HttpMethod.GET, "/trailers");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();
        assertThat(res.trailingHeaders().get(AsciiString.of("foo")), is("bar"));
    }

    private static void stream(StreamWriter<HttpObject> writer, long size, int chunkSize) {
        if (!writer.write(HttpData.of(new byte[chunkSize]))) {
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
        builder.decorator(
                (delegate, ctx, req) -> {
                    ctx.setWriteTimeoutMillis(clientWriteTimeoutMillis);
                    ctx.setResponseTimeoutMillis(clientResponseTimeoutMillis);
                    ctx.setMaxResponseLength(clientMaxResponseLength);
                    return delegate.execute(ctx, req);
                });

        return client = builder.build();
    }

    private static class CountingService extends AbstractHttpService {

        private final boolean slow;

        CountingService(boolean slow) {
            this.slow = slow;
        }

        @Override
        protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
            req.subscribe(new StreamConsumer(ctx.eventLoop(), slow) {
                @Override
                public void onError(Throwable cause) {
                    res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                MediaType.PLAIN_TEXT_UTF_8, Throwables.getStackTraceAsString(cause));
                }

                @Override
                public void onComplete() {
                    res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "%d", numReceivedBytes());
                }
            });
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
