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

package com.linecorp.armeria.client.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.google.common.io.ByteStreams;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.compression.Brotli;

class ContentPreviewingClientTest {

    private static final BiFunction<? super RequestContext, String, ?> CONTENT_SANITIZER =
            (ctx, content) -> {
                assertThat(ctx).isNotNull();
                assertThat(content).isNotNull();
                return "dummy content sanitizer";
            };

    private static final BiFunction<? super RequestContext, String, ?> FAILING_CONTENT_SANITIZER =
            (ctx, content) -> {
                throw new RuntimeException();
            };

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(
                    req.aggregate()
                       .thenApply(aggregated -> {
                           final ResponseHeaders responseHeaders =
                                   ResponseHeaders.of(HttpStatus.OK,
                                                      HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
                           return HttpResponse.of(responseHeaders,
                                                  HttpData.ofUtf8("Hello " + aggregated.contentUtf8() + '!'));
                       })));
            sb.decorator(EncodingService.builder()
                                        .minBytesToForceChunkedEncoding(1)
                                        .newDecorator());
        }
    };

    @Test
    void decodedContentPreview() {
        final BlockingWebClient client = WebClient.builder(server.httpUri())
                                                  .decorator(DecodingClient.newDecorator())
                                                  .decorator(ContentPreviewingClient.newDecorator(100))
                                                  .build()
                                                  .blocking();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.execute(headers, "Armeria");

            final RequestLog requestLog = captor.get().log().whenComplete().join();
            assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
            assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
            assertThat(requestLog.responseHeaders().get(HttpHeaderNames.CONTENT_ENCODING))
                    .isEqualTo(Brotli.isAvailable() ? "br" : "gzip");

            assertThat(res.contentUtf8()).isEqualTo("Hello Armeria!");
        }
    }

    /**
     * Unlike {@link #decodedContentPreview()}, the content preview of this test is encoded data because
     * the previewing decorator is inserted before {@link DecodingClient}.
     */
    @Test
    void contentPreviewIsDecodedInPreviewer() {
        final BlockingWebClient client = WebClient.builder(server.httpUri())
                                                  .decorator(decodingContentPreviewDecorator())
                                                  .decorator(DecodingClient.newDecorator())
                                                  .build()
                                                  .blocking();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/",
                                                         HttpHeaderNames.CONTENT_TYPE,
                                                         MediaType.PLAIN_TEXT_UTF_8);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.execute(headers, "Armeria");

            final RequestLog requestLog = captor.get().log().whenComplete().join();
            assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
            assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
            assertThat(requestLog.responseHeaders().get(HttpHeaderNames.CONTENT_ENCODING))
                    .isEqualTo(Brotli.isAvailable() ? "br" : "gzip");

            assertThat(res.contentUtf8()).isEqualTo("Hello Armeria!");
        }
    }

    @Test
    void sanitizeRequestContentPreview() {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(ContentPreviewingClient.builder(ContentPreviewerFactory.text(100))
                                                           .requestPreviewSanitizer(CONTENT_SANITIZER)
                                                           .newDecorator())
                         .build()
                         .blocking();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");

        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.execute(headers, "Armeria").contentUtf8())
                    .isEqualTo("Hello Armeria!");
            ctx = captor.get();
        }

        final RequestLog requestLog = ctx.log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("dummy content sanitizer");
        assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    @Test
    void sanitizeResponseContentPreview() {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(ContentPreviewingClient.builder(ContentPreviewerFactory.text(100))
                                                           .responsePreviewSanitizer(CONTENT_SANITIZER)
                                                           .newDecorator())
                         .build()
                         .blocking();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");

        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.execute(headers, "Armeria").contentUtf8())
                    .isEqualTo("Hello Armeria!");
            ctx = captor.get();
        }

        final RequestLog requestLog = ctx.log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
        assertThat(requestLog.responseContentPreview()).isEqualTo("dummy content sanitizer");
    }

    @Test
    void sanitizeContentPreview() {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(ContentPreviewingClient.builder(ContentPreviewerFactory.text(100))
                                                           .previewSanitizer(CONTENT_SANITIZER)
                                                           .newDecorator())
                         .build()
                         .blocking();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");

        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.execute(headers, "Armeria").contentUtf8())
                    .isEqualTo("Hello Armeria!");
            ctx = captor.get();
        }

        final RequestLog requestLog = ctx.log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("dummy content sanitizer");
        assertThat(requestLog.responseContentPreview()).isEqualTo("dummy content sanitizer");
    }

    @Test
    void failingRequestContentSanitizer() {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(ContentPreviewingClient.builder(ContentPreviewerFactory.text(100))
                                                           .requestPreviewSanitizer(FAILING_CONTENT_SANITIZER)
                                                           .newDecorator())
                         .build()
                         .blocking();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");

        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.execute(headers, "Armeria").contentUtf8())
                    .isEqualTo("Hello Armeria!");
            ctx = captor.get();
        }

        final RequestLog requestLog = ctx.log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
        assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    @Test
    void failingResponseContentSanitizer() {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(ContentPreviewingClient.builder(ContentPreviewerFactory.text(100))
                                                           .requestPreviewSanitizer(FAILING_CONTENT_SANITIZER)
                                                           .newDecorator())
                         .build()
                         .blocking();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");

        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.execute(headers, "Armeria").contentUtf8())
                    .isEqualTo("Hello Armeria!");
            ctx = captor.get();
        }

        final RequestLog requestLog = ctx.log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
        assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    private static Function<? super HttpClient, ContentPreviewingClient> decodingContentPreviewDecorator() {
        final BiPredicate<? super RequestContext, ? super HttpHeaders> previewerPredicate =
                (requestContext, headers) -> {
                    final String contentEncoding = headers.get(HttpHeaderNames.CONTENT_ENCODING);
                    return "br".equals(contentEncoding) || "gzip".equals(contentEncoding);
                };
        final BiFunction<HttpHeaders, ByteBuf, String> producer = (headers, data) -> {
            final String contentEncoding = headers.get(HttpHeaderNames.CONTENT_ENCODING);
            final byte[] bytes = new byte[data.readableBytes()];
            data.getBytes(0, bytes);
            final byte[] decoded;
            final InputStream in = new ByteArrayInputStream(bytes);
            try (InputStream unzipper = "br".equals(contentEncoding) ? new BrotliInputStream(in)
                                                                     : new GZIPInputStream(in)) {
                decoded = ByteStreams.toByteArray(unzipper);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
            return new String(decoded, StandardCharsets.UTF_8);
        };

        final ContentPreviewerFactory factory =
                ContentPreviewerFactory.builder()
                                       .maxLength(100)
                                       .binary(producer, previewerPredicate)
                                       .build();

        return ContentPreviewingClient.newDecorator(factory);
    }
}
