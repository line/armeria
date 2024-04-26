/*
 * Copyright 2024 LINE Corporation
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

import static com.linecorp.armeria.client.Http2ClientSettingsTest.readBytes;
import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.AbortedStreamException;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http2.Http2CodecUtil;

/**
 * This test is to check the behavior of the HttpClient when the 'Expect: 100-continue' header is set.
 */
final class HttpClientExpect100HeaderTest {

    @Nested
    class AggregatedHttpRequestHandlerTest {
        @Test
        void continueToSendHttp1Request() throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.of( "h1c://127.0.0.1:" + port);
                client.prepare()
                      .post("/")
                      .content("foo")
                      .header(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE)
                      .execute()
                      .aggregate();

                try (Socket s = ss.accept()) {
                    final BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    final OutputStream out = s.getOutputStream();
                    assertThat(in.readLine()).isEqualTo("POST / HTTP/1.1");
                    assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                    assertThat(in.readLine()).isEqualTo("content-type: text/plain; charset=utf-8");
                    assertThat(in.readLine()).isEqualTo("expect: 100-continue");
                    assertThat(in.readLine()).isEqualTo("content-length: 3");
                    assertThat(in.readLine()).startsWith("user-agent: armeria/");
                    assertThat(in.readLine()).isEmpty();

                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                    assertThat(in.readLine()).isEqualTo("foo");

                    out.write(("HTTP/1.1 201 Created\r\n" +
                               "Connection: close\r\n" +
                               "Content-Length: 0\r\n" +
                               "\r\n").getBytes(StandardCharsets.US_ASCII));

                    assertThat(in.readLine()).isNull();
                }
            }
        }

        @Test
        void failToSendHttp1Request() throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                final int port = ss.getLocalPort();

                final WebClient client = WebClient.of( "h1c://127.0.0.1:" + port);
                client.prepare()
                      .post("/")
                      .content("foo")
                      .header(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE)
                      .execute()
                      .aggregate();

                try (Socket s = ss.accept()) {
                    final BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    final OutputStream out = s.getOutputStream();
                    assertThat(in.readLine()).isEqualTo("POST / HTTP/1.1");
                    assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                    assertThat(in.readLine()).isEqualTo("content-type: text/plain; charset=utf-8");
                    assertThat(in.readLine()).isEqualTo("expect: 100-continue");
                    assertThat(in.readLine()).isEqualTo("content-length: 3");
                    assertThat(in.readLine()).startsWith("user-agent: armeria/");
                    assertThat(in.readLine()).isEmpty();

                    out.write(("HTTP/1.1 417 Expectation Failed\r\n" +
                               "Connection: close\r\n" +
                               "\r\n").getBytes(StandardCharsets.US_ASCII));

                    assertThat(in.readLine()).isNull();
                }
            }
        }

        @Test
        void continueToSendHttp2Request() throws Exception {
            try (ServerSocket ss = new ServerSocket(0);
                 ClientFactory clientFactory =
                         ClientFactory.builder()
                                      .useHttp2Preface(true)
                                      .http2InitialConnectionWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                                      .http2InitialStreamWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                                      .build()) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.builder("http://127.0.0.1:" + port)
                                                  .factory(clientFactory)
                                                  .build();
                final CompletableFuture<AggregatedHttpResponse> future =
                        client.prepare()
                              .post("/")
                              .content("foo")
                              .header(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE)
                              .execute()
                              .aggregate();

                try (Socket s = ss.accept()) {
                    final InputStream in = s.getInputStream();
                    final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());

                    // Read the connection preface and discard it.
                    readBytes(in, connectionPrefaceBuf().capacity());

                    // sendEmptySettingsAndAckFrame(bos);
                    readBytes(in, 9); // Read a SETTINGS_ACK frame and discard it.

                    // TODO: Check the request headers and body

                    future.join();
                }
            }
        }
    }

    @Nested
    class HttpRequestHandlerSubscriberTest {
        @Test
        void continueToSendHttp1StreamingRequest() throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.of( "h1c://127.0.0.1:" + port);
                final RequestHeaders headers =
                        RequestHeaders.builder(HttpMethod.POST, "/")
                                      .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                      .add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE.toString())
                                      .build();
                final HttpRequestWriter req = HttpRequest.streaming(headers);

                final CompletableFuture<AggregatedHttpResponse> future = client.execute(req).aggregate();

                req.write(HttpData.ofUtf8("foo"));
                req.close();

                try (Socket s = ss.accept()) {
                    final BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    final OutputStream out = s.getOutputStream();
                    assertThat(in.readLine()).isEqualTo("POST / HTTP/1.1");
                    assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                    assertThat(in.readLine()).isEqualTo("content-type: text/plain; charset=utf-8");
                    assertThat(in.readLine()).isEqualTo("expect: 100-continue");
                    assertThat(in.readLine()).startsWith("user-agent: armeria/");
                    assertThat(in.readLine()).isEqualTo("transfer-encoding: chunked");
                    assertThat(in.readLine()).isEmpty();

                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                    assertThat(in.readLine()).isEqualTo("3");
                    assertThat(in.readLine()).isEqualTo("foo");

                    out.write(("HTTP/1.1 201 Created\r\n" +
                               "Connection: close\r\n" +
                               "Content-Length: 0\r\n" +
                               "\r\n").getBytes(StandardCharsets.US_ASCII));

                    final AggregatedHttpResponse res = future.join();
                    assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
                }
            }
        }

        @Test
        void failToSendHttp1StreamingRequest() throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.of( "h1c://127.0.0.1:" + port);
                final RequestHeaders headers =
                        RequestHeaders.builder(HttpMethod.POST, "/")
                                      .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                      .add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE.toString())
                                      .build();
                final HttpRequestWriter req = HttpRequest.streaming(headers);

                final CompletableFuture<AggregatedHttpResponse> future = client.execute(req).aggregate();

                req.write(HttpData.ofUtf8("foo"));

                try (Socket s = ss.accept()) {
                    final BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    final OutputStream out = s.getOutputStream();
                    assertThat(in.readLine()).isEqualTo("POST / HTTP/1.1");
                    assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                    assertThat(in.readLine()).isEqualTo("content-type: text/plain; charset=utf-8");
                    assertThat(in.readLine()).isEqualTo("expect: 100-continue");
                    assertThat(in.readLine()).startsWith("user-agent: armeria/");
                    assertThat(in.readLine()).isEqualTo("transfer-encoding: chunked");
                    assertThat(in.readLine()).isEmpty();

                    out.write("HTTP/1.1 417 Expectation Failed\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                    assertThatThrownBy(future::join)
                            .hasCauseInstanceOf(AbortedStreamException.class);
                }
            }
        }
    }

    private HttpClientExpect100HeaderTest() {}
}
