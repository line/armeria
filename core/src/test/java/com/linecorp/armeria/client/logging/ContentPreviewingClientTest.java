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
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.google.common.io.ByteStreams;

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

class ContentPreviewingClientTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.from(
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
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(DecodingClient.newDecorator())
                                          .decorator(ContentPreviewingClient.newDecorator(100))
                                          .build();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");

        final ClientRequestContext context;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.execute(headers, "Armeria").aggregate().join();
            assertThat(res.contentUtf8()).isEqualTo("Hello Armeria!");
            assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("br");
            context = captor.get();
        }

        final RequestLog requestLog = context.log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
        assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    /**
     * Unlike {@link #decodedContentPreview()}, the content preview of this test is encoded data because
     * the previewing decorator is inserted before {@link DecodingClient}.
     */
    @Test
    void contentPreviewIsDecodedInPreviewer() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(decodingContentPreviewDecorator())
                                          .decorator(DecodingClient.newDecorator())
                                          .build();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/",
                                                         HttpHeaderNames.CONTENT_TYPE,
                                                         MediaType.PLAIN_TEXT_UTF_8);

        final ClientRequestContext context;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.execute(headers, "Armeria").aggregate().join();
            assertThat(res.contentUtf8()).isEqualTo("Hello Armeria!");
            assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("br");
            context = captor.get();
        }

        final RequestLog requestLog = context.log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
        assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    private static Function<? super HttpClient, ContentPreviewingClient> decodingContentPreviewDecorator() {
        final BiPredicate<? super RequestContext, ? super HttpHeaders> previewerPredicate =
                (requestContext, headers) -> "br".equals(headers.get(HttpHeaderNames.CONTENT_ENCODING));
        final BiFunction<HttpHeaders, ByteBuf, String> producer = (headers, data) -> {
            final byte[] bytes = new byte[data.readableBytes()];
            data.getBytes(0, bytes);
            final byte[] decoded;
            try (BrotliInputStream unzipper = new BrotliInputStream(new ByteArrayInputStream(bytes))) {
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
