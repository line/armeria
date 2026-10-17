/*
 * Copyright 2026 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.NetUtil;

final class DeferredExpect100ContinueTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/reject", (ctx, req) -> HttpResponse.of(HttpStatus.UNAUTHORIZED));
            sb.service("/no-content", (ctx, req) -> HttpResponse.of(HttpStatus.NO_CONTENT));
            sb.service("/delayed-reject", (ctx, req) -> HttpResponse.delayed(
                    HttpResponse.of(HttpStatus.UNAUTHORIZED), Duration.ofMillis(100)));
            sb.route()
              .path("/timeout")
              .requestTimeoutMillis(200)
              .build((ctx, req) -> HttpResponse.streaming());
            sb.service("/echo", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(agg -> HttpResponse.of(agg.contentUtf8()))));
            sb.service("/unary-echo", new HttpService() {
                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(req.aggregate().thenApply(agg -> {
                        // The 'expect' header should be invisible to a service. Note that this request is
                        // not aggregated by the server even if the exchange type is UNARY. Otherwise,
                        // the service would never be invoked because the content is never sent.
                        assertThat(agg.headers().contains(HttpHeaderNames.EXPECT)).isFalse();
                        return HttpResponse.of(agg.contentUtf8());
                    }));
                }

                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.UNARY;
                }
            });
            sb.route()
              .path("/decorated")
              .decorator((delegate, ctx, req) -> {
                  if (!req.headers().contains("authorization")) {
                      return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                  }
                  return delegate.serve(ctx, req);
              })
              .build((ctx, req) -> HttpResponse.of(
                      req.aggregate().thenApply(agg -> HttpResponse.of(agg.contentUtf8()))));
            sb.service("/throw", (ctx, req) -> {
                throw new AnticipatedException();
            });
            sb.service("/headers-first", (ctx, req) -> {
                // Send the response headers first and read the request content later.
                final HttpResponseWriter res = HttpResponse.streaming();
                res.write(ResponseHeaders.of(HttpStatus.OK));
                ctx.eventLoop().schedule(() -> {
                    req.aggregate().thenAccept(agg -> {
                        res.write(HttpData.ofUtf8(agg.contentUtf8()));
                        res.close();
                    });
                }, 100, TimeUnit.MILLISECONDS);
                return res;
            });
            sb.service("/early-hints", (ctx, req) -> {
                final HttpResponseWriter res = HttpResponse.streaming();
                res.write(ResponseHeaders.of(HttpStatus.valueOf(103)));
                // Read the request content after '103 Early Hints' is sent.
                ctx.eventLoop().schedule(() -> {
                    req.aggregate().thenAccept(agg -> {
                        res.write(ResponseHeaders.of(HttpStatus.OK));
                        res.write(HttpData.ofUtf8(agg.contentUtf8()));
                        res.close();
                    });
                }, 100, TimeUnit.MILLISECONDS);
                return res;
            });
            sb.service("/user-continue", (ctx, req) -> {
                // Send '100 Continue' explicitly before reading the request content.
                final HttpResponseWriter res = HttpResponse.streaming();
                res.write(ResponseHeaders.of(HttpStatus.CONTINUE));
                ctx.eventLoop().schedule(() -> {
                    req.aggregate().thenAccept(agg -> {
                        res.write(ResponseHeaders.of(HttpStatus.OK));
                        res.write(HttpData.ofUtf8(agg.contentUtf8()));
                        res.close();
                    });
                }, 100, TimeUnit.MILLISECONDS);
                return res;
            });
            sb.route()
              .path("/worker-group-echo")
              .serviceWorkerGroup(1)
              .build((ctx, req) -> HttpResponse.of(
                      req.aggregate().thenApply(agg -> HttpResponse.of(agg.contentUtf8()))));
            sb.route()
              .path("/auto-abort-delay")
              .requestAutoAbortDelay(Duration.ofMinutes(1))
              .build((ctx, req) -> HttpResponse.of(HttpStatus.UNAUTHORIZED));
            sb.route()
              .path("/too-large")
              .maxRequestLength(5)
              .build((ctx, req) -> HttpResponse.of(
                      req.aggregate().thenApply(agg -> HttpResponse.of(agg.contentUtf8()))));
        }
    };

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void respondWithoutSendingContinue(SessionProtocol protocol) {
        final WebClient client = WebClient.of(server.uri(protocol));
        assertNoContinue(client, "/reject", HttpStatus.UNAUTHORIZED);
        assertNoContinue(client, "/no-content", HttpStatus.NO_CONTENT);
        assertNoContinue(client, "/decorated", HttpStatus.UNAUTHORIZED);
        assertNoContinue(client, "/throw", HttpStatus.INTERNAL_SERVER_ERROR);
        assertNoContinue(client, "/not-found", HttpStatus.NOT_FOUND);
        assertNoContinue(client, "/too-large", HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        // 408 because the request content has not been received.
        assertNoContinue(client, "/timeout", HttpStatus.REQUEST_TIMEOUT);
    }

    private static void assertNoContinue(WebClient client, String path, HttpStatus status) {
        final AggregatedHttpResponse res =
                client.execute(RequestHeaders.of(HttpMethod.POST, path,
                                                 HttpHeaderNames.EXPECT, "100-continue",
                                                 HttpHeaderNames.CONTENT_LENGTH, 10),
                               "never sent")
                      .aggregate().join();
        assertThat(res.status()).isEqualTo(status);
        assertThat(res.informationals()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void sendContinueWhenContentIsSubscribed(SessionProtocol protocol) {
        final WebClient client = WebClient.of(server.uri(protocol));
        AggregatedHttpResponse res =
                client.execute(RequestHeaders.of(HttpMethod.POST, "/echo",
                                                 HttpHeaderNames.EXPECT, "100-continue"),
                               "hello")
                      .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.informationals()).containsExactly(ResponseHeaders.of(HttpStatus.CONTINUE));
        assertThat(res.contentUtf8()).isEqualTo("hello");

        res = client.execute(RequestHeaders.of(HttpMethod.POST, "/unary-echo",
                                               HttpHeaderNames.EXPECT, "100-continue"),
                             "hello")
                    .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.informationals()).containsExactly(ResponseHeaders.of(HttpStatus.CONTINUE));
        assertThat(res.contentUtf8()).isEqualTo("hello");

        res = client.execute(RequestHeaders.of(HttpMethod.POST, "/headers-first",
                                               HttpHeaderNames.EXPECT, "100-continue"),
                             "hello")
                    .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.informationals()).containsExactly(ResponseHeaders.of(HttpStatus.CONTINUE));
        assertThat(res.contentUtf8()).isEqualTo("hello");

        res = client.execute(RequestHeaders.of(HttpMethod.POST, "/early-hints",
                                               HttpHeaderNames.EXPECT, "100-continue"),
                             "hello")
                    .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.informationals()).containsExactly(ResponseHeaders.of(HttpStatus.valueOf(103)),
                                                         ResponseHeaders.of(HttpStatus.CONTINUE));
        assertThat(res.contentUtf8()).isEqualTo("hello");

        res = client.execute(RequestHeaders.of(HttpMethod.POST, "/worker-group-echo",
                                               HttpHeaderNames.EXPECT, "100-continue"),
                             "hello")
                    .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.informationals()).containsExactly(ResponseHeaders.of(HttpStatus.CONTINUE));
        assertThat(res.contentUtf8()).isEqualTo("hello");

        res = client.execute(RequestHeaders.of(HttpMethod.POST, "/decorated",
                                               HttpHeaderNames.EXPECT, "100-continue",
                                               HttpHeaderNames.AUTHORIZATION, "Bearer foo"),
                             "hello")
                    .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.informationals()).containsExactly(ResponseHeaders.of(HttpStatus.CONTINUE));
        assertThat(res.contentUtf8()).isEqualTo("hello");
    }

    @ParameterizedTest
    @ValueSource(strings = { "/reject", "/no-content", "/auto-abort-delay" })
    void closeHttp1ConnectionWhenContinueIsNotSent(String path) throws Exception {
        try (Socket s = new Socket(NetUtil.LOCALHOST, server.httpPort())) {
            // Shorter than the request auto abort delay of '/auto-abort-delay'.
            s.setSoTimeout(10000);
            final OutputStream out = s.getOutputStream();
            // Send only the headers without 'connection: close'.
            out.write(("POST " + path + " HTTP/1.1\r\n" +
                       "Host: 127.0.0.1\r\n" +
                       "Expect: 100-continue\r\n" +
                       "Content-Length: 1000000\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // The server should close the connection because the client may not send the content.
            final String response = new String(ByteStreams.toByteArray(s.getInputStream()),
                                               StandardCharsets.US_ASCII);
            final String statusLine = "/no-content".equals(path) ? "HTTP/1.1 204 No Content\r\n"
                                                                 : "HTTP/1.1 401 Unauthorized\r\n";
            assertThat(response).startsWith(statusLine)
                                .contains("connection: close\r\n")
                                .doesNotContain("100 Continue");
        }
    }

    @Test
    void keepHttp1ConnectionWhenContentIsSentWithoutWaiting() throws Exception {
        try (Socket s = new Socket(NetUtil.LOCALHOST, server.httpPort())) {
            s.setSoTimeout(10000);
            final OutputStream out = s.getOutputStream();
            // Send the content without waiting for '100 Continue' and a pipelined request.
            out.write(("POST /delayed-reject HTTP/1.1\r\n" +
                       "Host: 127.0.0.1\r\n" +
                       "Expect: 100-continue\r\n" +
                       "Content-Length: 5\r\n\r\n" +
                       "hello" +
                       "GET /not-found HTTP/1.1\r\n" +
                       "Host: 127.0.0.1\r\n" +
                       "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            final String responses = new String(ByteStreams.toByteArray(s.getInputStream()),
                                                StandardCharsets.US_ASCII);
            assertThat(responses).startsWith("HTTP/1.1 401 Unauthorized\r\n")
                                 .contains("HTTP/1.1 404 Not Found\r\n")
                                 .doesNotContain("100 Continue");
        }
    }

    @Test
    void doNotSendContinueTwiceWhenServiceSentContinue() throws Exception {
        try (Socket s = new Socket(NetUtil.LOCALHOST, server.httpPort())) {
            s.setSoTimeout(10000);
            final OutputStream out = s.getOutputStream();
            final InputStream in = s.getInputStream();
            out.write(("POST /user-continue HTTP/1.1\r\n" +
                       "Host: 127.0.0.1\r\n" +
                       "Expect: 100-continue\r\n" +
                       "Content-Length: 5\r\n" +
                       "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            final String continueResponse = "HTTP/1.1 100 Continue\r\n\r\n";
            final byte[] buf = new byte[continueResponse.length()];
            ByteStreams.readFully(in, buf);
            assertThat(new String(buf, StandardCharsets.US_ASCII)).isEqualTo(continueResponse);

            // The service subscribes to the request after 100 milliseconds.
            // Make sure the server does not send another '100 Continue'.
            s.setSoTimeout(1000);
            assertThatThrownBy(in::read).isInstanceOf(SocketTimeoutException.class);

            s.setSoTimeout(10000);
            out.write("hello".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            final String response = new String(ByteStreams.toByteArray(in), StandardCharsets.US_ASCII);
            assertThat(response).startsWith("HTTP/1.1 200 OK\r\n")
                                .endsWith("\r\n5\r\nhello\r\n0\r\n\r\n")
                                .doesNotContain("100 Continue");
        }
    }

    @Test
    void keepHttp1ConnectionWhenContinueIsSent() throws Exception {
        try (Socket s = new Socket(NetUtil.LOCALHOST, server.httpPort())) {
            s.setSoTimeout(10000);
            final OutputStream out = s.getOutputStream();
            final InputStream in = s.getInputStream();
            out.write(("POST /echo HTTP/1.1\r\n" +
                       "Host: 127.0.0.1\r\n" +
                       "Expect: 100-continue\r\n" +
                       "Content-Length: 5\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            final String continueResponse = "HTTP/1.1 100 Continue\r\n\r\n";
            final byte[] buf = new byte[continueResponse.length()];
            ByteStreams.readFully(in, buf);
            assertThat(new String(buf, StandardCharsets.US_ASCII)).isEqualTo(continueResponse);

            out.write("hello".getBytes(StandardCharsets.US_ASCII));
            out.write(("GET /not-found HTTP/1.1\r\n" +
                       "Host: 127.0.0.1\r\n" +
                       "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            final String responses = new String(ByteStreams.toByteArray(in), StandardCharsets.US_ASCII);
            assertThat(responses).startsWith("HTTP/1.1 200 OK\r\n");
            assertThat(responses).contains("\r\n\r\nhelloHTTP/1.1 404 Not Found\r\n");
        }
    }
}
