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
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DecodingClientTest {

    private static final int LARGE_TEXT_SIZE = 10000;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
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

            sb.service("/high-compression", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(Strings.repeat("1", LARGE_TEXT_SIZE));
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

    @MethodSource("allAvailableDecoders")
    @ParameterizedTest
    void httpDecodingTest(com.linecorp.armeria.common.encoding.StreamDecoderFactory factory) {
        final BlockingWebClient client =
                server.blockingWebClient(cb -> {
                    cb.decorator(DecodingClient.newDecorator(factory));
                });

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse response =
                    client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test"));
            assertContentEncoding(captor.get().log(), response, factory.encodingHeaderValue());
            assertThat(response.contentUtf8())
                    .isEqualTo("some content to compress more content to compress");
        }
    }

    private static List<com.linecorp.armeria.common.encoding.StreamDecoderFactory> allAvailableDecoders() {
        return com.linecorp.armeria.common.encoding.StreamDecoderFactory.all();
    }

    @Test
    void httpGzipDecodingTestWithOldDecoder() {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.decorator(DecodingClient.newDecorator(StreamDecoderFactory.gzip()));
        });

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse response =
                    client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test"));
            assertContentEncoding(captor.get().log(), response, "gzip");
            assertThat(response.contentUtf8()).isEqualTo("some content to compress more content to compress");
        }
    }

    @Test
    void httpDeflateDecodingTestWithOldDecoder() {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.decorator(DecodingClient.newDecorator(StreamDecoderFactory.deflate()));
        });

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse response =
                    client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test"));
            assertContentEncoding(captor.get().log(), response, "deflate");
            assertThat(response.contentUtf8()).isEqualTo("some content to compress more content to compress");
        }
    }

    @Test
    void disableAutoFillAcceptEncoding() {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.decorator(DecodingClient.builder()
                                       .autoFillAcceptEncoding(false)
                                       .newDecorator());
        });

        // Accept-encoding is unspecified.
        AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test"));
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();

        // 'gzip' is specified.
        final RequestHeaders header =
                RequestHeaders.of(HttpMethod.GET, "/encoding-test", HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            response = client.execute(header);
            assertContentEncoding(captor.get().log(), response, "gzip");
            assertThat(response.contentUtf8()).isEqualTo("some content to compress more content to compress");
        }
    }

    @Test
    void strictContentEncoding() {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.decorator(DecodingClient.builder()
                                       .strictContentEncoding(true)
                                       .newDecorator());
        });

        // Accept-encoding is unspecified.
        assertThatThrownBy(() -> client.execute(RequestHeaders.of(HttpMethod.GET, "/malformed-encoding")))
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

        final BlockingWebClient client = server.blockingWebClient(cb -> cb.decorator(decodingClient));
        // 'br' is specified.
        RequestHeaders header = RequestHeaders.of(HttpMethod.GET, "/echo-encoding",
                                                  HttpHeaderNames.ACCEPT_ENCODING, "br");
        AggregatedHttpResponse response = client.execute(header);
        assertThat(response.contentUtf8()).isEqualTo("none");

        // 'gzip,br' is specified.
        header = RequestHeaders.of(HttpMethod.GET, "/echo-encoding",
                                   HttpHeaderNames.ACCEPT_ENCODING, "gzip,br");
        response = client.execute(header);
        assertThat(response.contentUtf8()).isEqualTo("gzip");
    }

    @Test
    void shouldAllowDuplicatedEncodings() {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.decorator(DecodingClient.builder()
                                       .autoFillAcceptEncoding(false)
                                       .newDecorator());
        });

        // Request can have duplicated content encoding
        final HttpRequest request = HttpRequest.builder()
                                               .get("/encoding-test")
                                               .header(HttpHeaderNames.ACCEPT_ENCODING, "gzip,gzip")
                                               .build();

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse response = client.execute(request);
            assertContentEncoding(captor.get().log(), response, "gzip");
        }
    }

    @Test
    void shouldLimitDecodedContentLength() {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.decorator(DecodingClient.newDecorator());
            cb.maxResponseLength(LARGE_TEXT_SIZE - 1);
        });

        assertThatThrownBy(() -> client.get("/high-compression"))
                .isInstanceOfSatisfying(ContentTooLargeException.class, cause -> {
                    assertThat(cause.maxContentLength()).isEqualTo(LARGE_TEXT_SIZE - 1);
                });
    }

    private static void assertContentEncoding(RequestLogAccess log, AggregatedHttpResponse response,
                                              String contentEncoding) {
        final String originalContentEncoding = log.whenComplete().join().responseHeaders()
                                                  .get(HttpHeaderNames.CONTENT_ENCODING);
        // Response has correct encoding
        assertThat(originalContentEncoding).isEqualTo(contentEncoding);

        // Content-Encoding header should be removed from headers after decoding.
        final String finalContentEncoding = response.headers().get(HttpHeaderNames.CONTENT_ENCODING);
        assertThat(finalContentEncoding).isNull();
    }
}
