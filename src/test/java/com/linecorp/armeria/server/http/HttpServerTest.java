/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.junit.AfterClass;
import org.junit.Before;
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
import com.google.common.io.ByteStreams;
import com.google.common.net.MediaType;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.client.SessionOption;
import com.linecorp.armeria.client.SessionOptions;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.client.http.HttpClientFactory;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpObject;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.util.NativeLibraries;
import com.linecorp.armeria.internal.InboundTrafficController;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.encoding.HttpEncodingService;

import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.GlobalEventExecutor;

@RunWith(Parameterized.class)
public class HttpServerTest extends AbstractServerTest {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerTest.class);

    // TODO(trustin): Add SessionOption.NUM_WORKER_THREADS
    private static final ClientFactory clientFactory =
            new HttpClientFactory(SessionOptions.of(
                    SessionOption.TRUST_MANAGER_FACTORY.newValue(InsecureTrustManagerFactory.INSTANCE),
                    SessionOption.IDLE_TIMEOUT.newValue(Duration.ofSeconds(3)),
                    SessionOption.EVENT_LOOP_GROUP.newValue(
                            NativeLibraries.isEpollAvailable() ? new EpollEventLoopGroup(1)
                                                               : new NioEventLoopGroup(1))));

    private static final long MAX_CONTENT_LENGTH = 65536;
    private static final long STREAMING_CONTENT_LENGTH = Runtime.getRuntime().maxMemory() * 2;
    private static final int STREAMING_CONTENT_CHUNK_LENGTH =
            (int) Math.min(Integer.MAX_VALUE, STREAMING_CONTENT_LENGTH / 8);

    @Parameters(name = "{index}: {0}")
    public static Collection<SessionProtocol> parameters() {
        return EnumSet.complementOf(EnumSet.of(SessionProtocol.HTTP, SessionProtocol.HTTPS));
    }

    private static volatile long serverRequestTimeoutMillis;
    private static volatile long serverMaxRequestLength;
    private static volatile long clientWriteTimeoutMillis;
    private static volatile long clientResponseTimeoutMillis;
    private static volatile long clientMaxResponseLength;

    private final SessionProtocol protocol;
    private HttpClient client;

    public HttpServerTest(SessionProtocol protocol) {
        this.protocol = protocol;
    }

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {

        sb.numWorkers(1);
        sb.port(0, SessionProtocol.HTTP);
        sb.port(0, SessionProtocol.HTTPS);

        SelfSignedCertificate ssc = new SelfSignedCertificate();
        sb.sslContext(SessionProtocol.HTTPS, ssc.certificate(), ssc.privateKey());

        sb.serviceUnder("/delay", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                final long delayMillis = Long.parseLong(ctx.mappedPath().substring(1));
                ctx.eventLoop().schedule(() -> res.respond(HttpStatus.OK),
                                         delayMillis, TimeUnit.MILLISECONDS);
            }
        });

        sb.serviceUnder("/informed_delay", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                final long delayMillis = Long.parseLong(ctx.mappedPath().substring(1));

                // Send 9 informational responses before sending the actual response.
                for (int i = 1; i <= 9; i ++) {
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

        sb.serviceUnder("/content_delay", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                final long delayMillis = Long.parseLong(ctx.mappedPath().substring(1));

                res.write(HttpHeaders.of(HttpStatus.OK));

                // Send 10 characters ('0' - '9') at fixed rate.
                for (int i = 0; i < 10; i ++) {
                    final int finalI = i;
                    ctx.eventLoop().schedule(
                            () -> {
                                res.write(HttpData.ofAscii(String.valueOf(finalI)));
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
                                     .setInt(HttpHeaderNames.CONTENT_LENGTH, ctx.mappedPath().length()));
                res.close();
            }

            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                res.write(HttpHeaders.of(HttpStatus.OK)
                                     .setInt(HttpHeaderNames.CONTENT_LENGTH, ctx.mappedPath().length()));
                res.write(HttpData.ofAscii(ctx.mappedPath()));
                res.close();
            }
        });

        sb.serviceAt("/echo", new AbstractHttpService() {
            @Override
            protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                res.write(HttpHeaders.of(HttpStatus.OK));
                req.subscribe(new Subscriber<HttpObject>() {
                    private Subscription s;
                    @Override
                    public void onSubscribe(Subscription s) {
                        this.s = s;
                        s.request(1);
                    }

                    @Override
                    public void onNext(HttpObject http2Object) {
                        if (http2Object instanceof HttpData) {
                            res.write(http2Object);
                        }
                        s.request(1);
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

        sb.serviceAt("/count", new CountingService(false));
        sb.serviceAt("/slow_count", new CountingService(true));

        sb.serviceUnder("/zeroes", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                final long length = Long.parseLong(ctx.mappedPath().substring(1));
                res.write(HttpHeaders.of(HttpStatus.OK)
                                     .setLong(HttpHeaderNames.CONTENT_LENGTH, length));

                stream(res, length, STREAMING_CONTENT_CHUNK_LENGTH);
            }
        });

        sb.serviceAt("/strings", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                res.write(HttpHeaders.of(HttpStatus.OK).set(HttpHeaderNames.CONTENT_TYPE, "text/plain"));

                res.write(HttpData.ofUtf8("Armeria "));
                res.write(HttpData.ofUtf8("is "));
                res.write(HttpData.ofUtf8("awesome!"));
                res.close();
            }
        }.decorate(HttpEncodingService::new));

        sb.serviceAt("/images", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                res.write(HttpHeaders.of(HttpStatus.OK).set(HttpHeaderNames.CONTENT_TYPE, "image/png"));

                res.write(HttpData.ofUtf8("Armeria "));
                res.write(HttpData.ofUtf8("is "));
                res.write(HttpData.ofUtf8("awesome!"));
                res.close();
            }
        }.decorate(HttpEncodingService::new));

        sb.serviceAt("/small", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                String response = Strings.repeat("a", 1023);
                res.write(HttpHeaders.of(HttpStatus.OK)
                                     .set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                                     .setInt(HttpHeaderNames.CONTENT_LENGTH, response.length()));
                res.write(HttpData.ofUtf8(response));
                res.close();
            }
        }.decorate(HttpEncodingService::new));

        sb.serviceAt("/large", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                String response = Strings.repeat("a", 1024);
                res.write(HttpHeaders.of(HttpStatus.OK)
                                     .set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                                     .setInt(HttpHeaderNames.CONTENT_LENGTH, response.length()));
                res.write(HttpData.ofUtf8(response));
                res.close();
            }
        }.decorate(HttpEncodingService::new));

        final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator =
                delegate -> new DecoratingService<HttpRequest, HttpResponse, HttpRequest, HttpResponse>(delegate) {
                    @Override
                    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                        ctx.setRequestTimeoutMillis(serverRequestTimeoutMillis);
                        ctx.setMaxRequestLength(serverMaxRequestLength);
                        return delegate().serve(ctx, req);
                    }
                };
        sb.decorator(decorator);

        sb.defaultMaxRequestLength(MAX_CONTENT_LENGTH);
    }

    @AfterClass
    public static void destroy() {
        CompletableFuture.runAsync(clientFactory::close);
    }

    @Before
    public void reset() {
        serverRequestTimeoutMillis = 10000L;
        clientWriteTimeoutMillis = 3000L;
        clientResponseTimeoutMillis = 10000L;

        serverMaxRequestLength = MAX_CONTENT_LENGTH;
        clientMaxResponseLength = MAX_CONTENT_LENGTH;
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
        testStreamingRequest("/count");
    }

    @Test(timeout = 120000)
    public void testStreamingRequestWithSlowService() throws Exception {
        final int oldNumDeferredReads = InboundTrafficController.numDeferredReads();
        testStreamingRequest("/slow_count");
        // The connection's inbound traffic must be suspended due to overwhelming traffic from client.
        // If the number of deferred reads did not increase and the testStreaming() above did not fail,
        // it probably means the client failed to produce enough amount of traffic.
        assertThat(InboundTrafficController.numDeferredReads(), is(greaterThan(oldNumDeferredReads)));
    }

    @Test(timeout = 10000)
    public void testStrings_noAcceptEncoding() throws Exception {
        final DefaultHttpRequest req = new DefaultHttpRequest(HttpMethod.GET, "/strings");
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is(nullValue()));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is(nullValue()));
        assertThat(res.content().toStringUtf8(), is("Armeria is awesome!"));
    }

    @Test(timeout = 10000)
    public void testStrings_acceptEncodingGzip() throws Exception {
        final DefaultHttpRequest req = new DefaultHttpRequest(
                HttpHeaders.of(HttpMethod.GET, "/strings")
                           .set(HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
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
        final DefaultHttpRequest req = new DefaultHttpRequest(
                HttpHeaders.of(HttpMethod.GET, "/images")
                           .set(HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is(nullValue()));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is(nullValue()));
        assertThat(res.content().toStringUtf8(), is("Armeria is awesome!"));
    }

    @Test(timeout = 10000)
    public void testStrings_acceptEncodingGzip_smallFixedContent() throws Exception {
        final DefaultHttpRequest req = new DefaultHttpRequest(
                HttpHeaders.of(HttpMethod.GET, "/small")
                           .set(HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is(nullValue()));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is(nullValue()));
        assertThat(res.content().toStringUtf8(), is(Strings.repeat("a", 1023)));
    }

    @Test(timeout = 10000)
    public void testStrings_acceptEncodingGzip_largeFixedContent() throws Exception {
        final DefaultHttpRequest req = new DefaultHttpRequest(
                HttpHeaders.of(HttpMethod.GET, "/large")
                           .set(HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
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
        final DefaultHttpRequest req = new DefaultHttpRequest(
                HttpHeaders.of(HttpMethod.GET, "/strings")
                           .set(HttpHeaderNames.ACCEPT_ENCODING, "deflate"));
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
        final DefaultHttpRequest req = new DefaultHttpRequest(
                HttpHeaders.of(HttpMethod.GET, "/strings")
                           .set(HttpHeaderNames.ACCEPT_ENCODING, "piedpiper"));
        final CompletableFuture<AggregatedHttpMessage> f = client().execute(req).aggregate();

        final AggregatedHttpMessage res = f.get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING), is(nullValue()));
        assertThat(res.headers().get(HttpHeaderNames.VARY), is(nullValue()));
        assertThat(res.content().toStringUtf8(), is("Armeria is awesome!"));
    }

    private void testStreamingRequest(String path) throws InterruptedException, ExecutionException {
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
        testStreamingResponse(false);
    }

    @Test(timeout = 120000)
    public void testStreamingResponseWithSlowClient() throws Exception {
        final int oldNumDeferredReads = InboundTrafficController.numDeferredReads();
        testStreamingResponse(true);
        // The connection's inbound traffic must be suspended due to overwhelming traffic from client.
        // If the number of deferred reads did not increase and the testStreaming() above did not fail,
        // it probably means the client failed to produce enough amount of traffic.
        assertThat(InboundTrafficController.numDeferredReads(), is(greaterThan(oldNumDeferredReads)));
    }


    private void testStreamingResponse(boolean slowClient) throws InterruptedException, ExecutionException {
        // Disable timeouts and length limits so that test does not fail due to slow transfer.
        clientWriteTimeoutMillis = 0;
        clientResponseTimeoutMillis = 0;
        clientMaxResponseLength = 0;
        serverRequestTimeoutMillis = 0;

        final DefaultHttpRequest req = new DefaultHttpRequest(HttpMethod.GET,
                                                              "/zeroes/" + STREAMING_CONTENT_LENGTH);
        final HttpResponse res = client().execute(req);
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

        res.closeFuture().get();
        assertThat(status.get(), is(HttpStatus.OK));
        assertThat(consumer.numReceivedBytes(), is(STREAMING_CONTENT_LENGTH));
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

        final ClientBuilder builder = new ClientBuilder(
                "none+" + protocol.uriText() + "://127.0.0.1:" + (protocol.isTls() ? httpsPort() : httpPort()));

        builder.factory(clientFactory);
        builder.decorator(HttpRequest.class, HttpResponse.class,
                          delegate -> new DecoratingClient<HttpRequest, HttpResponse, HttpRequest, HttpResponse>(delegate) {
            @Override
            public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
                ctx.setWriteTimeoutMillis(clientWriteTimeoutMillis);
                ctx.setResponseTimeoutMillis(clientResponseTimeoutMillis);
                ctx.setMaxResponseLength(clientMaxResponseLength);
                return delegate().execute(ctx, req);
            }
        });

        return client = builder.build(HttpClient.class);
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

        private Subscription s;
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
        public void onSubscribe(Subscription s) {
            this.s = s;
            s.request(1);
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
                    executor.schedule(() -> s.request(1), 1, TimeUnit.SECONDS);
                } else {
                    s.request(1);
                }

                logger.debug("{} bytes received", numReceivedBytes);
            } else {
                s.request(1);
            }
        }
    }
}
