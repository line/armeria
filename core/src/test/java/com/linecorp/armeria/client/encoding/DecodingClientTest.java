/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client.encoding;

import static com.google.common.base.MoreObjects.firstNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DecodingClientTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/encoding-test", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK),
                            HttpData.ofUtf8("some content to compress "),
                            HttpData.ofUtf8("more content to compress"));
                }
            }.decorate(EncodingService.newDecorator()));

            sb.service("/malformed-encoding", (ctx, req) -> {
                return HttpResponse.of(
                        ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_ENCODING, "unsupported"),
                        HttpData.ofUtf8("unsupported content encoding"));
            });

            sb.service("/echo-encoding", (ctx, req) -> {
                return HttpResponse
                        .of(firstNonNull(req.headers().get(HttpHeaderNames.ACCEPT_ENCODING), "none"));
            });
        }
    };

    @Test
    void httpGzipDecodingTest() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(DecodingClient.newDecorator(
                                                  com.linecorp.armeria.common.encoding.StreamDecoderFactory
                                                          .gzip()))
                                          .build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(response.contentUtf8()).isEqualTo("some content to compress more content to compress");
    }

    @Test
    void httpDeflateDecodingTest() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(DecodingClient.newDecorator(
                                                  com.linecorp.armeria.common.encoding.StreamDecoderFactory
                                                          .deflate()))
                                          .build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("deflate");
        assertThat(response.contentUtf8()).isEqualTo("some content to compress more content to compress");
    }

    @Test
    @EnabledIf("io.netty.handler.codec.compression.Brotli#isAvailable")
    void httpBrotliDecodingTest() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(DecodingClient.newDecorator(
                                                  com.linecorp.armeria.common.encoding.StreamDecoderFactory
                                                          .brotli()))
                                          .build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("br");
        assertThat(response.contentUtf8()).isEqualTo("some content to compress more content to compress");
    }

    @Test
    void httpGzipDecodingTestWithOldDecoder() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(DecodingClient.newDecorator(StreamDecoderFactory.gzip()))
                                          .build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(response.contentUtf8()).isEqualTo("some content to compress more content to compress");
    }

    @Test
    void httpDeflateDecodingTestWithOldDecoder() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(
                                                  DecodingClient.newDecorator(StreamDecoderFactory.deflate())
                                          ).build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("deflate");
        assertThat(response.contentUtf8()).isEqualTo("some content to compress more content to compress");
    }

    @Test
    void disableAutoFillAcceptEncoding() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(DecodingClient.builder()
                                                                   .autoFillAcceptEncoding(false)
                                                                   .newDecorator())
                                          .build();

        // Accept-encoding is unspecified.
        AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();

        // 'gzip' is specified.
        final RequestHeaders header =
                RequestHeaders.of(HttpMethod.GET, "/encoding-test", HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        response = client.execute(header).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(response.contentUtf8()).isEqualTo("some content to compress more content to compress");
    }

    @Test
    void strictContentEncoding() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(DecodingClient.builder()
                                                                   .strictContentEncoding(true)
                                                                   .newDecorator())
                                          .build();

        // Accept-encoding is unspecified.
        final CompletableFuture<AggregatedHttpResponse> response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/malformed-encoding")).aggregate();

        assertThatThrownBy(response::join)
                .isInstanceOf(CompletionException.class)
                .getCause()
                .isInstanceOf(UnsupportedEncodingException.class)
                .hasMessage("encoding: unsupported");
    }

    @Test
    void shouldFilterOutUnsupportedAcceptEncoding() {
        final Function<? super HttpClient, DecodingClient> decodingClient =
                DecodingClient.builder()
                              .decoderFactories(
                                      com.linecorp.armeria.common.encoding.StreamDecoderFactory.gzip())
                              .autoFillAcceptEncoding(false)
                              .newDecorator();

        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(decodingClient)
                                          .build();
        // 'br' is specified.
        RequestHeaders header = RequestHeaders.of(HttpMethod.GET, "/echo-encoding",
                                                  HttpHeaderNames.ACCEPT_ENCODING, "br");
        AggregatedHttpResponse response = client.execute(header).aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("none");

        // 'gzip,br' is specified.
        header = RequestHeaders.of(HttpMethod.GET, "/echo-encoding",
                                   HttpHeaderNames.ACCEPT_ENCODING, "gzip,br");
        response = client.execute(header).aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("gzip");
    }

    @Test
    void shouldAllowDuplicatedEncodings() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(DecodingClient.builder()
                                          .autoFillAcceptEncoding(false)
                                          .newDecorator())
                                          .build();

        // Request can have duplicated content encoding
        final HttpRequest request = HttpRequest.builder()
                                               .get("/encoding-test")
                                               .header(HttpHeaderNames.ACCEPT_ENCODING, "gzip,gzip")
                                               .build();

        // Response has correct encoding
        final AggregatedHttpResponse response = client.execute(request).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    }
}
