/*
 * Copyright 2015 LINE Corporation
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

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Throwables;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;

import com.linecorp.armeria.client.encoding.DeflateStreamDecoderFactory;
import com.linecorp.armeria.client.encoding.HttpDecodingClient;
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
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;
import com.linecorp.armeria.server.encoding.HttpEncodingService;
import com.linecorp.armeria.testing.server.ServerRule;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.util.ReferenceCountUtil;

public class HttpClientIntegrationTest {

    private static final String TEST_USER_AGENT_NAME = "ArmeriaTest";

    private static final AtomicReference<ByteBuf> releasedByteBuf = new AtomicReference<>();

    // Used to communicate with test when the response can't be used.
    private static final AtomicReference<Boolean> completed = new AtomicReference<>();

    private static final class PoolUnawareDecorator extends SimpleDecoratingService<HttpRequest, HttpResponse> {

        private PoolUnawareDecorator(Service<HttpRequest, HttpResponse> delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            HttpResponse res = delegate().serve(ctx, req);
            HttpResponseWriter decorated = HttpResponse.streaming();
            res.subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject httpObject) {
                    decorated.write(httpObject);
                }

                @Override
                public void onError(Throwable t) {
                    decorated.close(t);
                }

                @Override
                public void onComplete() {
                    decorated.close();
                }
            });
            return decorated;
        }
    }

    private static final class PoolAwareDecorator extends SimpleDecoratingService<HttpRequest, HttpResponse> {

        private PoolAwareDecorator(Service<HttpRequest, HttpResponse> delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            HttpResponse res = delegate().serve(ctx, req);
            HttpResponseWriter decorated = HttpResponse.streaming();
            res.subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject httpObject) {
                    if (httpObject instanceof ByteBufHolder) {
                        try {
                            decorated.write(HttpData.of(((ByteBufHolder) httpObject).content()));
                        } finally {
                            ReferenceCountUtil.safeRelease(httpObject);
                        }
                    } else {
                        decorated.write(httpObject);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    decorated.close(t);
                }

                @Override
                public void onComplete() {
                    decorated.close();
                }
            }, true);
            return decorated;
        }
    }

    private static class PooledContentService extends AbstractHttpService {

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            ByteBuf buf = ctx.alloc().buffer();
            buf.writeCharSequence("pooled content", StandardCharsets.UTF_8);
            releasedByteBuf.set(buf);
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, new ByteBufHttpData(buf, false));
        }
    }

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, HTTP);

            sb.service("/httptestbody", new AbstractHttpService() {

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return doGetOrPost(req);
                }

                @Override
                protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
                    return doGetOrPost(req);
                }

                private HttpResponse doGetOrPost(HttpRequest req) {
                        final MediaType contentType = req.headers().contentType();
                    if (contentType != null) {
                        throw new IllegalArgumentException(
                                "Serialization format is none, so content type should not be set: " +
                                contentType);
                    }

                    final String accept = req.headers().get(HttpHeaderNames.ACCEPT);
                    if (!"utf-8".equals(accept)) {
                        throw new IllegalArgumentException(
                                "Serialization format is none, so accept should not be overridden: " +
                                accept);
                    }

                    return HttpResponse.from(req.aggregate().handle((aReq, cause) -> {
                        if (cause != null) {
                            return HttpResponse.of(
                                    HttpStatus.INTERNAL_SERVER_ERROR,
                                    MediaType.PLAIN_TEXT_UTF_8, Throwables.getStackTraceAsString(cause));
                        }

                        return HttpResponse.of(
                                HttpHeaders.of(HttpStatus.OK)
                                           .set(HttpHeaderNames.CACHE_CONTROL, "alwayscache"),
                                HttpData.ofUtf8(
                                        "METHOD: %s|ACCEPT: %s|BODY: %s",
                                        req.method().name(), accept,
                                        aReq.content().toString(StandardCharsets.UTF_8)));
                    }).exceptionally(CompletionActions::log));
                }
            });

            sb.service("/not200", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.NOT_FOUND);
                }
            });

            sb.service("/useragent", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    String ua = req.headers().get(HttpHeaderNames.USER_AGENT, "undefined");
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, ua);
                }
            });

            sb.service("/hello/world", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "success");
                }
            });

            sb.service("/encoding", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(
                            HttpHeaders.of(HttpStatus.OK),
                            HttpData.ofUtf8("some content to compress "),
                            HttpData.ofUtf8("more content to compress"));
                }
            }.decorate(HttpEncodingService.class));

            sb.service("/encoding-toosmall", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "small content");
                }
            }.decorate(HttpEncodingService.class));

            sb.service("/pooled", new PooledContentService());

            sb.service("/pooled-aware", new PooledContentService().decorate(PoolAwareDecorator::new));

            sb.service("/pooled-unaware", new PooledContentService().decorate(PoolUnawareDecorator::new));

            sb.service("/stream-closed", ((ctx, req) -> {
                ctx.setRequestTimeout(Duration.ZERO);
                HttpResponseWriter res = HttpResponse.streaming();
                res.write(HttpHeaders.of(HttpStatus.OK));
                req.subscribe(new Subscriber<HttpObject>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(HttpObject httpObject) {
                    }

                    @Override
                    public void onError(Throwable t) {
                        completed.set(true);
                    }

                    @Override
                    public void onComplete() {
                    }
                }, ctx.eventLoop());
                return res;
            }));
        }
    };

    private static final ClientFactory clientFactory = ClientFactory.DEFAULT;

    @Before
    public void clearError() {
        releasedByteBuf.set(null);
    }

    /**
     * When the content of a request is empty, the encoded request should never have 'content-length' or
     * 'transfer-encoding' header.
     */
    @Test
    public void testRequestNoBodyWithoutExtraHeaders() throws Exception {
        testSocketOutput(
                "/foo",
                port -> "GET /foo HTTP/1.1\r\n" +
                        "host: 127.0.0.1:" + port + "\r\n" +
                        "user-agent: " + HttpHeaderUtil.USER_AGENT + "\r\n\r\n");
    }

    @Test
    public void testRequestNoBody() throws Exception {
        HttpClient client = HttpClient.of(server.uri("/"));

        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.GET, "/httptestbody")
                           .set(HttpHeaderNames.ACCEPT, "utf-8")).aggregate().get();

        assertEquals(HttpStatus.OK, response.headers().status());
        assertEquals("alwayscache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertEquals("METHOD: GET|ACCEPT: utf-8|BODY: ",
                     response.content().toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testRequestWithBody() throws Exception {
        HttpClient client = HttpClient.of(server.uri("/"));

        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, "/httptestbody")
                           .set(HttpHeaderNames.ACCEPT, "utf-8"),
                "requestbody日本語").aggregate().get();

        assertEquals(HttpStatus.OK, response.headers().status());
        assertEquals("alwayscache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertEquals("METHOD: POST|ACCEPT: utf-8|BODY: requestbody日本語",
                     response.content().toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testNot200() throws Exception {
        HttpClient client = HttpClient.of(server.uri("/"));

        AggregatedHttpMessage response = client.get("/not200").aggregate().get();

        assertEquals(HttpStatus.NOT_FOUND, response.headers().status());
    }

    /**
     * When the request path contains double slashes, they should be replaced with single slashes.
     */
    @Test
    public void testDoubleSlashSuppression() throws Exception {
        testDoubleSlashSuppression("/double//slashes", "/double/slashes");
        // The double slashes in the query string should not be normalized.
        testDoubleSlashSuppression("/double//slashes?slashed//query", "/double/slashes?slashed//query");
    }

    private static void testDoubleSlashSuppression(String path, String normalizedPath) throws IOException {
        testSocketOutput(
                path,
                port -> "GET " + normalizedPath + " HTTP/1.1\r\n" +
                        "host: 127.0.0.1:" + port + "\r\n" +
                        "user-agent: " + HttpHeaderUtil.USER_AGENT + "\r\n\r\n"
        );
    }

    /**
     * User-agent header should be overridden by ClientOption.HTTP_HEADER
     */
    @Test
    public void testUserAgentOverridableByClientOption() throws Exception {

        HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.USER_AGENT, TEST_USER_AGENT_NAME);
        ClientOptions options = ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(headers));
        HttpClient client = HttpClient.of(server.uri("/"), options);

        AggregatedHttpMessage response = client.get("/useragent").aggregate().get();

        assertEquals(TEST_USER_AGENT_NAME, response.content().toStringUtf8());
    }

    @Test
    public void testUserAgentOverridableByRequestHeader() throws Exception {

        HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.USER_AGENT, TEST_USER_AGENT_NAME);
        ClientOptions options = ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(headers));
        HttpClient client = HttpClient.of(server.uri("/"), options);

        final String OVERIDDEN_USER_AGENT_NAME = "Overridden";

        AggregatedHttpMessage response =
                client.execute(HttpHeaders.of(HttpMethod.GET, "/useragent")
                                          .add(HttpHeaderNames.USER_AGENT, OVERIDDEN_USER_AGENT_NAME))
                      .aggregate().get();

        assertEquals(OVERIDDEN_USER_AGENT_NAME, response.content().toStringUtf8());
    }

    @Test
    public void httpDecoding() throws Exception {
        HttpClient client = new HttpClientBuilder(server.uri("/"))
                .factory(clientFactory).decorator(HttpDecodingClient.newDecorator()).build();

        AggregatedHttpMessage response =
                client.execute(HttpHeaders.of(HttpMethod.GET, "/encoding")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(response.content().toStringUtf8()).isEqualTo(
                "some content to compress more content to compress");
    }

    @Test
    public void httpDecoding_deflate() throws Exception {
        HttpClient client = new HttpClientBuilder(server.uri("/"))
                .factory(clientFactory)
                .decorator(HttpDecodingClient.newDecorator(new DeflateStreamDecoderFactory())).build();

        AggregatedHttpMessage response =
                client.execute(HttpHeaders.of(HttpMethod.GET, "/encoding")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("deflate");
        assertThat(response.content().toStringUtf8()).isEqualTo(
                "some content to compress more content to compress");
    }

    @Test
    public void httpDecoding_noEncodingApplied() throws Exception {
        HttpClient client = new HttpClientBuilder(server.uri("/"))
                .factory(clientFactory)
                .decorator(HttpDecodingClient.newDecorator(new DeflateStreamDecoderFactory())).build();

        AggregatedHttpMessage response =
                client.execute(HttpHeaders.of(HttpMethod.GET, "/encoding-toosmall")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(response.content().toStringUtf8()).isEqualTo("small content");
    }

    private static void testSocketOutput(String path,
                                         IntFunction<String> expectedResponse) throws IOException {
        Socket s = null;
        try (ServerSocket ss = new ServerSocket(0)) {
            final int port = ss.getLocalPort();
            final String expected = expectedResponse.apply(port);

            // Send a request. Note that we do not wait for a response anywhere because we are only interested
            // in testing what client sends.
            Clients.newClient(clientFactory, "none+h1c://127.0.0.1:" + port, HttpClient.class).get(path);
            ss.setSoTimeout(10000);
            s = ss.accept();

            final byte[] buf = new byte[expected.length()];
            final InputStream in = s.getInputStream();

            // Read the encoded request.
            s.setSoTimeout(10000);
            ByteStreams.readFully(in, buf);

            // Ensure that the encoded request matches.
            assertThat(new String(buf, StandardCharsets.US_ASCII)).isEqualTo(expected);

            // Should not send anything more.
            s.setSoTimeout(1000);
            assertThatThrownBy(in::read).isInstanceOf(SocketTimeoutException.class);
        } finally {
            Closeables.close(s, true);
        }
    }

    @Test
    public void givenHttpClientUriPathAndRequestPath_whenGet_thenRequestToConcatenatedPath() throws Exception {
        HttpClient client = HttpClient.of(server.uri("/hello"));

        AggregatedHttpMessage response = client.get("/world").aggregate().get();

        assertEquals("success", response.content().toStringUtf8());
    }

    @Test
    public void givenRequestPath_whenGet_thenRequestToPath() throws Exception {
        HttpClient client = HttpClient.of(server.uri("/"));

        AggregatedHttpMessage response = client.get("/hello/world").aggregate().get();

        assertEquals("success", response.content().toStringUtf8());
    }

    @Test
    public void testPooledResponseDefaultSubscriber() throws Exception {
        HttpClient client = HttpClient.of(server.uri("/"));

        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.GET, "/pooled")).aggregate().get();

        assertEquals(HttpStatus.OK, response.headers().status());
        assertThat(response.content().toStringUtf8()).isEqualTo("pooled content");
        await().untilAsserted(() -> assertThat(releasedByteBuf.get().refCnt()).isZero());
    }

    @Test
    public void testPooledResponsePooledSubscriber() throws Exception {
        HttpClient client = HttpClient.of(server.uri("/"));

        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.GET, "/pooled-aware")).aggregate().get();

        assertEquals(HttpStatus.OK, response.headers().status());
        assertThat(response.content().toStringUtf8()).isEqualTo("pooled content");
        await().untilAsserted(() -> assertThat(releasedByteBuf.get().refCnt()).isZero());
    }

    @Test
    public void testUnpooledResponsePooledSubscriber() throws Exception {
        HttpClient client = HttpClient.of(server.uri("/"));

        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.GET, "/pooled-unaware")).aggregate().get();

        assertEquals(HttpStatus.OK, response.headers().status());
        assertThat(response.content().toStringUtf8()).isEqualTo("pooled content");
        await().untilAsserted(() -> assertThat(releasedByteBuf.get().refCnt()).isZero());
    }

    @Test
    public void testCloseClientFactory() throws Exception {
        ClientFactory factory = new ClientFactoryBuilder().build();
        HttpClient client = factory.newClient("none+" + server.uri("/"), HttpClient.class);
        HttpRequestWriter req = HttpRequest.streaming(HttpHeaders.of(HttpMethod.GET, "/stream-closed"));
        HttpResponse res = client.execute(req);
        AtomicReference<HttpObject> obj = new AtomicReference<>();
        res.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                obj.set(httpObject);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
        req.write(HttpData.ofUtf8("not finishing this stream, sorry."));
        await().untilAsserted(() -> assertThat(obj).hasValue(HttpHeaders.of(HttpStatus.OK)));
        factory.close();
        await().untilAsserted(() -> assertThat(completed).hasValue(true));
    }
}
