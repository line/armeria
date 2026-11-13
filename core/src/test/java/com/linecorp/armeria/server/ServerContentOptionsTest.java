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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.encoding.StreamDecoder;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.common.encoding.StreamEncoderFactories;
import com.linecorp.armeria.common.encoding.StreamEncoderFactory;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.encoding.Compression;
import com.linecorp.armeria.server.logging.ContentPreview;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.util.AttributeKey;

class ServerContentOptionsTest {
    private static final String CONTENT = "hello server content options";
    private static final AttributeKey<ByteBuf> RESPONSE_BUFFER =
            AttributeKey.valueOf(ServerContentOptionsTest.class, "RESPONSE_BUFFER");

    private static final StreamEncoderFactory FAILING_ENCODER = new StreamEncoderFactory() {
        @Override
        public String encodingHeaderValue() {
            return "broken";
        }

        @Override
        public OutputStream newEncoder(ByteBufOutputStream output) {
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    throw new IOException("encoding failed");
                }
            };
        }
    };

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.option(ServerOptions.CONTENT_PREVIEW, ContentPreview.of(ContentPreviewerFactory.text(100)))
              .option(ServerOptions.COMPRESSION, Compression.builder()
                      .encoderFactories(StreamEncoderFactories.GZIP).minBytesToForceChunkedEncoding(1).build())
              .option(ServerOptions.DECOMPRESSION, ImmutableList.of(StreamDecoderFactory.gzip()));
            for (ExchangeType type : ExchangeType.values()) {
                sb.route().get("/broken/" + type)
                  .option(ServerOptions.COMPRESSION, Compression.builder()
                          .encoderFactories(FAILING_ENCODER).minBytesToForceChunkedEncoding(1).build())
                  .build(new HttpService() {
                      @Override
                      public ExchangeType exchangeType(RoutingContext ctx) {
                          return type;
                      }

                      @Override
                      public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                          return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK,
                                  HttpHeaderNames.CONTENT_TYPE, "text/plain"),
                                  HttpData.ofUtf8(CONTENT), HttpHeaders.of("x-trailer", "done"));
                      }
                  });
                sb.serviceUnder("/" + type, new HttpService() {
                    @Override
                    public ExchangeType exchangeType(RoutingContext ctx) {
                        return type;
                    }

                    @Override
                    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                        assertThat(ctx.exchangeType()).isEqualTo(type);
                        if (!type.isRequestStreaming()) {
                            assertThat(req.isOpen()).isFalse();
                        }
                        if (req.path().endsWith("/error")) {
                            throw new IllegalStateException("service failed");
                        }
                        if (req.path().endsWith("/trailers")) {
                            final ResponseHeaders headers = ResponseHeaders.of(
                                    HttpStatus.OK, HttpHeaderNames.CONTENT_TYPE, "text/plain");
                            return HttpResponse.of(headers, HttpData.ofUtf8(CONTENT),
                                                   HttpHeaders.of("x-trailer", "done"));
                        }
                        if (req.path().endsWith("/empty")) {
                            return HttpResponse.of(HttpStatus.NO_CONTENT);
                        }
                        if (req.method() == HttpMethod.HEAD) {
                            return HttpResponse.of(CONTENT);
                        }
                        return HttpResponse.of(req.aggregate().thenApply(aggregated -> {
                            assertThat(aggregated.headers().contains(HttpHeaderNames.CONTENT_ENCODING))
                                    .isFalse();
                            return HttpResponse.of(aggregated.contentUtf8());
                        }));
                    }
                });
            }
            sb.errorHandler((ctx, cause) -> {
                if (cause instanceof ContentTooLargeException) {
                    return null;
                }
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                       MediaType.PLAIN_TEXT_UTF_8, "mapped error");
            });
            sb.route().get("/timeout").requestTimeoutMillis(50)
              .build((ctx, req) -> HttpResponse.streaming());
            sb.route().get("/decorated")
              .decorator((delegate, ctx, req) -> delegate.serve(ctx, req)
                      .mapData(data -> HttpData.ofUtf8("changed")))
              .build((ctx, req) -> HttpResponse.of("original"));
            sb.route().get("/compression-failure")
              .option(ServerOptions.COMPRESSION, Compression.builder()
                      .encodableContentTypes(type -> {
                          throw new IllegalStateException("compression predicate");
                      }).build())
              .build((ctx, req) -> HttpResponse.of("original"));
            sb.route().get("/async-compression-failure")
              .option(ServerOptions.COMPRESSION, Compression.builder()
                      .encodableContentTypes(type -> {
                          throw HttpResponseException.of(HttpResponse.of(
                                  HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8,
                                  "compression failed"));
                      }).build())
              .build((ctx, req) -> {
                  final ByteBuf buffer = ctx.alloc().buffer()
                                            .writeBytes(CONTENT.getBytes(StandardCharsets.UTF_8));
                  ctx.setAttr(RESPONSE_BUFFER, buffer);
                  return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK,
                                                            HttpHeaderNames.CONTENT_TYPE, "text/plain"),
                                         HttpData.wrap(buffer));
              });
            sb.service("/body-failure", (ctx, req) -> {
                final HttpResponseWriter response = HttpResponse.streaming();
                response.write(ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_TYPE, "text/plain"));
                response.write(HttpData.ofUtf8("partial"));
                ctx.eventLoop().schedule(() -> response.close(new IllegalStateException("body failed")),
                                         100, TimeUnit.MILLISECONDS);
                return response;
            });
            sb.route().get("/all-encoders")
              .option(ServerOptions.COMPRESSION, Compression.builder()
                      .minBytesToForceChunkedEncoding(1).build())
              .build((ctx, req) -> HttpResponse.of(CONTENT));
            sb.route().post("/preview-only")
              .option(ServerOptions.DECOMPRESSION, ImmutableList.of())
              .build(new HttpService() {
                  @Override
                  public ExchangeType exchangeType(RoutingContext ctx) {
                      return ExchangeType.UNARY;
                  }

                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                      return HttpResponse.of(req.aggregate().thenApply(agg ->
                              HttpResponse.of(agg.contentUtf8())));
                  }
              });
            sb.service("/vary", (ctx, req) -> {
                ctx.addAdditionalResponseHeader(HttpHeaderNames.VARY, "Origin");
                return HttpResponse.of(CONTENT);
            });
            sb.service("/already-encoded", (ctx, req) -> HttpResponse.of(
                    ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_ENCODING, "gzip",
                                       HttpHeaderNames.CONTENT_TYPE, "text/plain"),
                    HttpData.wrap(gzip(CONTENT))));
            sb.route().post("/limit").maxRequestLength(100)
              .build((ctx, req) -> HttpResponse.of(req.aggregate().thenApply(agg ->
                      HttpResponse.of("unexpected"))));
            sb.route().get("/fallback").errorHandler((ctx, cause) -> HttpResponse.ofFailure(cause))
              .build((ctx, req) -> HttpResponse.ofFailure(new IllegalStateException("fallback")));
            sb.route().get("/sanitized")
              .option(ServerOptions.CONTENT_PREVIEW, ContentPreview.builder(ContentPreviewerFactory.text(100))
                      .responsePreviewSanitizer((ctx, preview) -> "redacted").build())
              .build((ctx, req) -> HttpResponse.of("secret"));
            sb.route().get("/sanitizer-failure")
              .option(ServerOptions.CONTENT_PREVIEW, ContentPreview.builder(ContentPreviewerFactory.text(100))
                      .responsePreviewSanitizer((ctx, preview) -> {
                          throw new IllegalStateException("sanitize");
                      })
                      .build())
              .build((ctx, req) -> HttpResponse.of("secret"));
            sb.route().get("/disabled")
              .option(ServerOptions.CONTENT_PREVIEW, ContentPreview.disabled())
              .option(ServerOptions.COMPRESSION, Compression.disabled())
              .build((ctx, req) -> HttpResponse.of(CONTENT));
        }
    };

    @ParameterizedTest
    @EnumSource(ExchangeType.class)
    void decompressPreviewAndCompress(ExchangeType type) throws Exception {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/" + type + "/echo",
                HttpHeaderNames.CONTENT_ENCODING, "gzip", HttpHeaderNames.ACCEPT_ENCODING, "gzip",
                HttpHeaderNames.CONTENT_TYPE, "text/plain");
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(headers, HttpData.wrap(gzip(CONTENT)));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(gunzip(response)).isEqualTo(CONTENT);
        final RequestLog log = nextLog();
        assertThat(log.requestContentPreview()).isEqualTo(CONTENT);
        assertThat(log.responseContentPreview()).isEqualTo(CONTENT);
        assertThat(log.responseLength()).isEqualTo(response.content().length());
    }

    @ParameterizedTest
    @EnumSource(ExchangeType.class)
    void errorResponseIsPreviewedAndCompressed(ExchangeType type) throws Exception {
        final AggregatedHttpResponse response = server.blockingWebClient().execute(
                RequestHeaders.of(HttpMethod.POST, "/" + type + "/error",
                                  HttpHeaderNames.CONTENT_ENCODING, "gzip",
                                  HttpHeaderNames.ACCEPT_ENCODING, "gzip"),
                HttpData.wrap(gzip(CONTENT)));
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(gunzip(response)).isEqualTo("mapped error");
        assertThat(nextLog().responseContentPreview()).isEqualTo("mapped error");
    }

    @ParameterizedTest
    @EnumSource(ExchangeType.class)
    void trailersFollowCompressionFooter(ExchangeType type) throws Exception {
        final AggregatedHttpResponse response = get("/" + type + "/trailers");
        assertThat(gunzip(response)).isEqualTo(CONTENT);
        assertThat(response.trailers().get("x-trailer")).isEqualTo("done");
        assertThat(nextLog().responseContentPreview()).isEqualTo(CONTENT);
    }

    @ParameterizedTest
    @EnumSource(ExchangeType.class)
    void headDoesNotPreviewDiscardedBody(ExchangeType type) throws Exception {
        final AggregatedHttpResponse response = server.blockingWebClient().execute(
                RequestHeaders.of(HttpMethod.HEAD, "/" + type + "/head",
                                  HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
        assertThat(response.content().isEmpty()).isTrue();
        assertThat(response.headers().contains(HttpHeaderNames.CONTENT_ENCODING)).isFalse();
        assertThat(nextLog().responseContentPreview()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ExchangeType.class)
    void noContent(ExchangeType type) throws Exception {
        assertThat(get("/" + type + "/empty").content().isEmpty()).isTrue();
        nextLog();
    }

    @Test
    void sanitizerAndDisable() throws Exception {
        get("/sanitized");
        assertThat(nextLog().responseContentPreview()).isEqualTo("redacted");
        assertThat(get("/sanitizer-failure").status()).isEqualTo(HttpStatus.OK);
        assertThat(nextLog().responseContentPreview()).isNull();
        final AggregatedHttpResponse response = get("/disabled");
        assertThat(response.contentUtf8()).isEqualTo(CONTENT);
        assertThat(response.headers().contains(HttpHeaderNames.CONTENT_ENCODING)).isFalse();
        assertThat(nextLog().responseContentPreview()).isNull();
    }

    @Test
    void lateFallbackResponse() throws Exception {
        final AggregatedHttpResponse response = get("/fallback");
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(nextLog().responseContentPreview()).isEqualTo(gunzip(response));
    }

    @Test
    void decompressedSizeLimit() throws Exception {
        final String content = new String(new char[1000]).replace('\0', 'a');
        final byte[] encoded = gzip(content);
        assertThat(encoded.length).isLessThan(100);
        final AggregatedHttpResponse response = server.blockingWebClient().execute(
                RequestHeaders.of(HttpMethod.POST, "/limit", HttpHeaderNames.CONTENT_ENCODING, "gzip"),
                HttpData.wrap(encoded));
        assertThat(response.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(nextLog().responseCause()).isInstanceOf(ContentTooLargeException.class);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void protocolsAndReplacementResponses(SessionProtocol protocol) throws Exception {
        final BlockingWebClient client = WebClient.of(server.uri(protocol)).blocking();
        for (String path : new String[] { "/BIDI_STREAMING/trailers", "/UNARY/trailers", "/timeout" }) {
            final AggregatedHttpResponse response = client.execute(RequestHeaders.of(
                    HttpMethod.GET, path, HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
            assertThat(nextLog().responseContentPreview()).isEqualTo(gunzip(response));
        }
    }

    @Test
    void finalDecoratorOutput() throws Exception {
        assertThat(gunzip(get("/decorated"))).isEqualTo("changed");
        assertThat(nextLog().responseContentPreview()).isEqualTo("changed");
    }

    @Test
    void compressionFailureUsesFreshErrorPreview() throws Exception {
        final AggregatedHttpResponse response = get("/compression-failure");
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.headers().contains(HttpHeaderNames.CONTENT_ENCODING)).isFalse();
        assertThat(nextLog().responseContentPreview()).isEqualTo(response.contentUtf8());
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void compressionFailureCancelsOriginalResponseAndCompletesReplacement(SessionProtocol protocol)
            throws Exception {
        final AggregatedHttpResponse response = WebClient.of(server.uri(protocol)).blocking()
                .get("/async-compression-failure");
        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.contentUtf8()).isEqualTo("compression failed");
        final RequestLog log = nextLog();
        assertThat(log.responseCause()).isNull();
        assertThat(log.responseContentPreview()).isEqualTo("compression failed");
        final ByteBuf buffer = log.context().attr(RESPONSE_BUFFER);
        assertThat(buffer).isNotNull();
        assertThat(buffer.refCnt()).isZero();
    }

    @Test
    void preservesVaryAndDoesNotDoubleEncode() throws Exception {
        final AggregatedHttpResponse response = get("/vary");
        assertThat(response.headers().getAll(HttpHeaderNames.VARY)).contains("Origin", "accept-encoding");
        assertThat(gunzip(response)).isEqualTo(CONTENT);
        nextLog();
        assertThat(gunzip(get("/already-encoded"))).isEqualTo(CONTENT);
        assertThat(nextLog().responseContentPreview()).isNull();
    }

    @Test
    void failureAfterHeadersKeepsPartialPreview() throws Exception {
        assertThatThrownBy(() -> get("/body-failure")).isInstanceOf(Exception.class);
        final RequestLog log = nextLog();
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.OK);
        assertThat(log.responseContentPreview()).isEqualTo("partial");
        assertThat(log.responseCause()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(StreamEncoderFactories.class)
    void builtInEncoders(StreamEncoderFactories encoder) throws Exception {
        final AggregatedHttpResponse response = server.blockingWebClient().execute(RequestHeaders.of(
                HttpMethod.GET, "/all-encoders", HttpHeaderNames.ACCEPT_ENCODING,
                encoder.encodingHeaderValue()));
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING))
                .isEqualTo(encoder.encodingHeaderValue());
        final StreamDecoderFactory factory = StreamDecoderFactory.all().stream()
                .filter(candidate -> candidate.encodingHeaderValue().equals(encoder.encodingHeaderValue()))
                .findFirst().get();
        final StreamDecoder decoder = factory.newDecoder(ByteBufAllocator.DEFAULT);
        final HttpData data = decoder.decode(response.content());
        final HttpData lastData = decoder.finish();
        try {
            assertThat(data.toStringUtf8() + lastData.toStringUtf8()).isEqualTo(CONTENT);
        } finally {
            data.close();
            lastData.close();
        }
        assertThat(nextLog().responseContentPreview()).isEqualTo(CONTENT);
    }

    @Test
    void previewOnlyAggregatedRequest() throws Exception {
        server.blockingWebClient().execute(RequestHeaders.of(HttpMethod.POST, "/preview-only",
                                                             HttpHeaderNames.CONTENT_TYPE, "text/plain"),
                                            HttpData.ofUtf8(CONTENT));
        final RequestLog log = nextLog();
        assertThat(log.requestContentPreview()).isEqualTo(CONTENT);
        assertThat(log.responseContentPreview()).isEqualTo(CONTENT);
    }

    @ParameterizedTest
    @EnumSource(ExchangeType.class)
    void encoderFailureDoesNotSendTrailersOrReportSuccess(ExchangeType type) throws Exception {
        assertThatThrownBy(() -> server.blockingWebClient().execute(RequestHeaders.of(
                HttpMethod.GET, "/broken/" + type, HttpHeaderNames.ACCEPT_ENCODING, "broken")))
                .isInstanceOf(Exception.class);
        assertThat(nextLog().responseCause()).isInstanceOf(IOException.class);
    }

    private static AggregatedHttpResponse get(String path) {
        return server.blockingWebClient().execute(RequestHeaders.of(HttpMethod.GET, path,
                                                                    HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
    }

    private static RequestLog nextLog() throws Exception {
        return server.requestContextCaptor().poll().log().whenComplete().get(10, TimeUnit.SECONDS);
    }

    private static byte[] gzip(String content) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static String gunzip(AggregatedHttpResponse response) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(response.content().array()))) {
            return new String(ByteStreams.toByteArray(gzip), StandardCharsets.UTF_8);
        }
    }
}
