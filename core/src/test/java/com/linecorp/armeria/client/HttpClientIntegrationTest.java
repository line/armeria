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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import javax.net.ssl.SSLException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;

import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.client.encoding.StreamDecoderFactory;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
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
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.HttpHeaderUtil;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;

class HttpClientIntegrationTest {

    private static final AtomicReference<ByteBuf> releasedByteBuf = new AtomicReference<>();

    // Used to communicate with test when the response can't be used.
    private static volatile boolean completed;

    private static final class PoolUnawareDecorator extends SimpleDecoratingHttpService {

        private PoolUnawareDecorator(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final HttpResponse res = unwrap().serve(ctx, req);
            final HttpResponseWriter decorated = HttpResponse.streaming();
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

    private static final class PoolAwareDecorator extends SimpleDecoratingHttpService {

        private PoolAwareDecorator(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final HttpResponse res = unwrap().serve(ctx, req);
            final HttpResponseWriter decorated = HttpResponse.streaming();
            res.subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject httpObject) {
                    if (httpObject instanceof HttpData) {
                        try (HttpData data = (HttpData) httpObject) {
                            decorated.write(HttpData.copyOf(data.byteBuf()));
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
            }, SubscriptionOption.WITH_POOLED_OBJECTS);
            return decorated;
        }
    }

    private static class PooledContentService extends AbstractHttpService {

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            final ByteBuf buf = ctx.alloc().buffer();
            buf.writeCharSequence("pooled content", StandardCharsets.UTF_8);
            releasedByteBuf.set(buf);
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, HttpData.wrap(buf));
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
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
                    final MediaType contentType = req.contentType();
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
                                    MediaType.PLAIN_TEXT_UTF_8, Exceptions.traceText(cause));
                        }

                        return HttpResponse.of(
                                ResponseHeaders.of(HttpStatus.OK,
                                                   HttpHeaderNames.CACHE_CONTROL, "alwayscache"),
                                HttpData.ofUtf8(
                                        "METHOD: %s|ACCEPT: %s|BODY: %s",
                                        req.method().name(), accept,
                                        aReq.contentUtf8()));
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
                    final String ua = req.headers().get(HttpHeaderNames.USER_AGENT, "undefined");
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, ua);
                }
            });

            sb.service("/authority", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final String ua = req.headers().get(HttpHeaderNames.AUTHORITY, "undefined");
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
                            ResponseHeaders.of(HttpStatus.OK),
                            HttpData.ofUtf8("some content to compress "),
                            HttpData.ofUtf8("more content to compress"));
                }
            }.decorate(EncodingService.newDecorator()));

            sb.service("/encoding-toosmall", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "small content");
                }
            }.decorate(EncodingService.newDecorator()));

            sb.service("/pooled", new PooledContentService());

            sb.service("/pooled-aware", new PooledContentService().decorate(PoolAwareDecorator::new));

            sb.service("/pooled-unaware", new PooledContentService().decorate(PoolUnawareDecorator::new));

            sb.service("/stream-closed", (ctx, req) -> {
                ctx.clearRequestTimeout();
                final HttpResponseWriter res = HttpResponse.streaming();
                res.write(ResponseHeaders.of(HttpStatus.OK));
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
                        completed = true;
                    }

                    @Override
                    public void onComplete() {
                    }
                }, ctx.eventLoop());
                return res;
            });

            sb.service("glob:/oneparam/**", (ctx, req) -> {
                // The client was able to send a request with an escaped path param. Armeria servers always
                // decode the path so ctx.path == '/oneparam/foo/bar' here.
                if ("/oneparam/foo%2Fbar".equals(req.headers().path()) &&
                    "/oneparam/foo/bar".equals(ctx.path())) {
                    return HttpResponse.of("routed");
                }
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
            });

            // To check https://github.com/line/armeria/issues/1895
            sb.serviceUnder("/", (ctx, req) -> {
                if (completed) {
                    return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                } else {
                    completed = true;
                    return HttpResponse.of(HttpStatus.OK);
                }
            });

            sb.service("/client-aborted", (ctx, req) -> {
                // Don't need to return a real response since the client will timeout.
                completed = true;
                return HttpResponse.streaming();
            });

            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.disableServerHeader();
            sb.disableDateHeader();
        }
    };

    private static final ClientFactory clientFactory = ClientFactory.ofDefault();

    @BeforeEach
    void clearError() {
        completed = false;
        releasedByteBuf.set(null);
    }

    /**
     * When the content of a request is empty, the encoded request should never have 'content-length' or
     * 'transfer-encoding' header.
     */
    @Test
    void testRequestNoBodyWithoutExtraHeaders() throws Exception {
        testSocketOutput(
                "/foo",
                port -> "GET /foo HTTP/1.1\r\n" +
                        "host: 127.0.0.1:" + port + "\r\n" +
                        "user-agent: " + HttpHeaderUtil.USER_AGENT + "\r\n\r\n");
    }

    @Test
    void testRequestNoBody() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.GET, "/httptestbody",
                                  HttpHeaderNames.ACCEPT, "utf-8")).aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("alwayscache");
        assertThat(response.contentUtf8()).isEqualTo("METHOD: GET|ACCEPT: utf-8|BODY: ");
    }

    @Test
    void testRequestWithBody() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST, "/httptestbody",
                                  HttpHeaderNames.ACCEPT, "utf-8"),
                "requestbody日本語").aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("alwayscache");
        assertThat(response.contentUtf8()).isEqualTo("METHOD: POST|ACCEPT: utf-8|BODY: requestbody日本語");
    }

    @Test
    void testResolvedEndpointWithAlternateAuthority() throws Exception {
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("localhost", server.httpPort())
                                                             .withIpAddr("127.0.0.1"));
        testEndpointWithAlternateAuthority(group);
    }

    @Test
    void testUnresolvedEndpointWithAlternateAuthority() throws Exception {
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("localhost", server.httpPort()));
        testEndpointWithAlternateAuthority(group);
    }

    private static void testEndpointWithAlternateAuthority(EndpointGroup group) {
        final WebClient client = WebClient.builder(SessionProtocol.HTTP, group)
                                          .setHeader(HttpHeaderNames.AUTHORITY,
                                                     "255.255.255.255.xip.io")
                                          .build();

        final AggregatedHttpResponse res = client.get("/hello/world").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("success");
    }

    @Test
    void testNot200() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response = client.get("/not200").aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * :authority header should be overridden by ClientOption.HTTP_HEADER
     */
    @Test
    void testAuthorityOverridableByClientOption() throws Exception {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .addressResolverGroupFactory(
                                                          unused -> MockAddressResolverGroup.localhost())
                                                  .build()) {

            // An authority header should not be overridden on a client with a base URI.
            WebClient client = WebClient.builder(server.httpUri())
                                        .setHeader(HttpHeaderNames.AUTHORITY, "foo:8080")
                                        .factory(factory)
                                        .build();

            AggregatedHttpResponse response = client.get("/authority").aggregate().get();
            assertThat(response.contentUtf8()).isEqualTo("127.0.0.1:" + server.httpPort());

            // An authority header should not be overridden on a client with a base URI.
            final String additionalAuthority = "foo:" + server.httpPort();
            client = WebClient.builder()
                              .setHeader(HttpHeaderNames.AUTHORITY, additionalAuthority)
                              .factory(factory)
                              .build();

            response = client.get(server.httpUri().resolve("/authority").toString()).aggregate().get();
            assertThat(response.contentUtf8()).isEqualTo(additionalAuthority);
        }
    }

    /**
     * User-agent header should be overridden by ClientOption.HTTP_HEADER
     */
    @Test
    void testUserAgentOverridableByClientOption() throws Exception {
        testHeaderOverridableByClientOption("/useragent", HttpHeaderNames.USER_AGENT, "foo-agent");
    }

    private static void testHeaderOverridableByClientOption(String path, AsciiString headerName,
                                                            String headerValue) throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .setHeader(headerName, headerValue)
                                          .build();

        final AggregatedHttpResponse response = client.get(path).aggregate().get();

        assertThat(response.contentUtf8()).isEqualTo(headerValue);
    }

    @Test
    void httpDecoding() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(DecodingClient.newDecorator())
                                          .build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("br");
        assertThat(response.contentUtf8()).isEqualTo(
                "some content to compress more content to compress");
    }

    @Test
    void httpDecoding_gzip() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(DecodingClient.newDecorator(
                                                  StreamDecoderFactory.gzip()))
                                          .build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(response.contentUtf8()).isEqualTo(
                "some content to compress more content to compress");
    }

    @Test
    void httpDecoding_deflate() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(DecodingClient.newDecorator(
                                                  StreamDecoderFactory.deflate()))
                                          .build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("deflate");
        assertThat(response.contentUtf8()).isEqualTo(
                "some content to compress more content to compress");
    }

    @Test
    void httpDecoding_noEncodingApplied() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(DecodingClient.newDecorator(
                                                  StreamDecoderFactory.deflate()))
                                          .build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-toosmall")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(response.contentUtf8()).isEqualTo("small content");
    }

    private static void testSocketOutput(String path,
                                         IntFunction<String> expectedResponse) throws IOException {
        Socket s = null;
        try (ServerSocket ss = new ServerSocket(0)) {
            final int port = ss.getLocalPort();
            final String expected = expectedResponse.apply(port);

            // Send a request. Note that we do not wait for a response anywhere because we are only interested
            // in testing what client sends.
            final HttpResponse res = WebClient.builder("none+h1c://127.0.0.1:" + port)
                                              .factory(clientFactory)
                                              .build()
                                              .get(path);
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

            res.abort();
        } finally {
            Closeables.close(s, true);
        }
    }

    @Test
    void givenHttpClientUriPathAndRequestPath_whenGet_thenRequestToConcatenatedPath() throws Exception {
        final WebClient client = WebClient.of(server.httpUri() + "/hello");

        final AggregatedHttpResponse response = client.get("/world").aggregate().get();

        assertThat(response.contentUtf8()).isEqualTo("success");
    }

    @Test
    void givenRequestPath_whenGet_thenRequestToPath() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response = client.get("/hello/world").aggregate().get();

        assertThat(response.contentUtf8()).isEqualTo("success");
    }

    @Test
    void testPooledResponseDefaultSubscriber() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.GET, "/pooled")).aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("pooled content");
        await().untilAsserted(() -> assertThat(releasedByteBuf.get().refCnt()).isZero());
    }

    @Test
    void testPooledResponsePooledSubscriber() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.GET, "/pooled-aware")).aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("pooled content");
        await().untilAsserted(() -> assertThat(releasedByteBuf.get().refCnt()).isZero());
    }

    @Test
    void testUnpooledResponsePooledSubscriber() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.GET, "/pooled-unaware")).aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("pooled content");
        await().untilAsserted(() -> assertThat(releasedByteBuf.get().refCnt()).isZero());
    }

    @Test
    void testCloseClientFactory() throws Exception {
        final ClientFactory factory = ClientFactory.builder().build();
        final WebClient client = WebClient.builder(server.httpUri()).factory(factory).build();
        final HttpRequestWriter req = HttpRequest.streaming(RequestHeaders.of(HttpMethod.GET,
                                                                              "/stream-closed"));
        final HttpResponse res = client.execute(req);
        final AtomicReference<HttpObject> obj = new AtomicReference<>();
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
        await().untilAsserted(() -> assertThat(obj).hasValue(ResponseHeaders.of(HttpStatus.OK)));
        factory.close();
        await().until(() -> completed);
    }

    @Test
    void testEscapedPathParam() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response = client.get("/oneparam/foo%2Fbar").aggregate().get();

        assertThat(response.contentUtf8()).isEqualTo("routed");
    }

    @Test
    void testUpgradeRequestExecutesLogicOnlyOnce() throws Exception {
        final ClientFactory clientFactory = ClientFactory.builder()
                                                         .useHttp2Preface(false)
                                                         .build();
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(DecodingClient.newDecorator())
                                          .build();

        final AggregatedHttpResponse response = client.execute(
                AggregatedHttpRequest.of(HttpMethod.GET, "/only-once/request")).aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        clientFactory.closeAsync();
    }

    @Test
    void testDefaultClientFactoryOptions() throws Exception {
        final ClientFactory clientFactory = ClientFactory.builder()
                                                         .options(ClientFactoryOptions.of())
                                                         .build();
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .build();

        final AggregatedHttpResponse response = client.execute(
                AggregatedHttpRequest.of(HttpMethod.GET, "/hello/world")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        clientFactory.closeAsync();
    }

    @Test
    void testEmptyClientFactoryOptions() throws Exception {
        final ClientFactory clientFactory = ClientFactory.builder()
                                                         .options(ClientFactoryOptions.of(ImmutableList.of()))
                                                         .build();
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .build();

        final AggregatedHttpResponse response = client.execute(
                AggregatedHttpRequest.of(HttpMethod.GET, "/hello/world")).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        clientFactory.closeAsync();
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void requestAbortWithException(boolean isAbort) {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpRequestWriter request = HttpRequest.streaming(HttpMethod.GET, "/client-aborted");
        final HttpResponse response = client.execute(request);

        final IllegalStateException badState = new IllegalStateException("bad state");
        if (isAbort) {
            request.abort(badState);
        } else {
            request.close(badState);
        }
        assertThatThrownBy(() -> response.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(badState);
    }

    @Test
    void responseAbortWithException() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpRequest request = HttpRequest.streaming(HttpMethod.GET, "/client-aborted");
        final HttpResponse response = client.execute(request);

        await().until(() -> completed);
        final IllegalStateException badState = new IllegalStateException("bad state");
        response.abort(badState);
        assertThatThrownBy(() -> response.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(badState);
    }

    @Test
    void httpsRequestToPlainTextEndpoint() throws Exception {
        final WebClient client = WebClient.builder(SessionProtocol.HTTPS, server.httpEndpoint())
                                          .factory(ClientFactory.insecure()).build();
        final Throwable throwable = catchThrowable(() -> client.get("/hello/world").aggregate().get());
        assertThat(Exceptions.peel(throwable))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCauseInstanceOf(SSLException.class);
    }

    @Test
    void httpsRequestWithInvalidCertificate() throws Exception {
        final WebClient client = WebClient.builder(
                SessionProtocol.HTTPS, server.httpEndpoint()).build();
        final Throwable throwable = catchThrowable(() -> client.get("/hello/world").aggregate().get());
        assertThat(Exceptions.peel(throwable))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCauseInstanceOf(SSLException.class);
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    @SuppressWarnings({
            "InnerClassMayBeStatic", // A nested test class must not be static.
            "UnnecessaryFullyQualifiedName", // Using FQCN for Jetty Server to avoid confusion.
    })
    class JettyInteropTest {

        @Nullable
        org.eclipse.jetty.server.Server jetty;

        @BeforeAll
        void startJetty() throws Exception {
            jetty = new org.eclipse.jetty.server.Server(0);
            jetty.setHandler(new AbstractHandler() {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request,
                                   HttpServletResponse response) throws IOException, ServletException {
                    if (Collections.list(request.getHeaders("host")).size() == 1) {
                        response.setStatus(HttpStatus.OK.code());
                    } else {
                        response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.code());
                    }
                    baseRequest.setHandled(true);
                }
            });
            jetty.start();
        }

        @AfterAll
        void stopJetty() throws Exception {
            if (jetty != null) {
                jetty.stop();
            }
        }

        @Test
        void http1SendsOneHostHeaderWhenUserSetsIt() {
            final WebClient client = WebClient.of(
                    "h1c://localhost:" + ((ServerConnector) jetty.getConnectors()[0]).getLocalPort() + '/');

            final AggregatedHttpResponse response = client.execute(
                    RequestHeaders.of(HttpMethod.GET, "/onlyonehost", HttpHeaderNames.HOST, "foobar")
            ).aggregate().join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
        }
    }

    private static void checkGetRequest(String path, WebClient client) throws Exception {
        final AggregatedHttpResponse response = client.get(path).aggregate().get();
        assertThat(response.contentUtf8()).isEqualTo("success");
    }
}
