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

package com.linecorp.armeria.server.logging;

import static com.linecorp.armeria.common.util.UnmodifiableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.google.common.io.ByteStreams;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
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
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.Functions;
import com.linecorp.armeria.internal.logging.ContentPreviewingUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.compression.Brotli;

class ContentPreviewingServiceTest {

    private static final AtomicReference<RequestContext> contextCaptor = new AtomicReference<>();

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
            final HttpService httpService = (ctx, req) -> HttpResponse.of(
                    req.aggregate()
                       .thenApply(aggregated -> {
                           final ResponseHeaders responseHeaders =
                                   ResponseHeaders.of(HttpStatus.OK,
                                                      HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
                           return HttpResponse.of(responseHeaders,
                                                  HttpData.ofUtf8("Hello " + aggregated.contentUtf8() + '!'));
                       }));

            sb.service("/beforeEncoding", httpService);
            sb.decorator("/beforeEncoding", ContentPreviewingService.newDecorator(100));
            sb.decorator("/beforeEncoding", EncodingService.builder()
                                                           .minBytesToForceChunkedEncoding(1)
                                                           .newDecorator());

            sb.service("/encoded", httpService);
            sb.decorator("/encoded", EncodingService.builder()
                                                    .minBytesToForceChunkedEncoding(1)
                                                    .newDecorator());
            sb.decorator("/encoded", decodingContentPreviewDecorator());

            sb.service("/requestPreviewSanitizer", httpService);
            sb.decorator("/requestPreviewSanitizer",
                         ContentPreviewingService.builder(ContentPreviewerFactory.text(100))
                                                 .requestPreviewSanitizer(CONTENT_SANITIZER)
                                                 .newDecorator());

            sb.service("/responsePreviewSanitizer", httpService);
            sb.decorator("/responsePreviewSanitizer",
                         ContentPreviewingService.builder(ContentPreviewerFactory.text(100))
                                                 .responsePreviewSanitizer(CONTENT_SANITIZER)
                                                 .newDecorator());

            sb.service("/previewSanitizer", httpService);
            sb.decorator("/previewSanitizer",
                         ContentPreviewingService.builder(ContentPreviewerFactory.text(100))
                                                 .previewSanitizer(CONTENT_SANITIZER)
                                                 .newDecorator());

            sb.service("/deferred", httpService);
            sb.decorator("/deferred", ContentPreviewingService.newDecorator(100));
            sb.decorator("/deferred", (delegate, ctx, req) -> HttpResponse.of(
                    completedFuture(null).handleAsync((ignored, cause) -> {
                        try {
                            return delegate.serve(ctx, req);
                        } catch (Exception e) {
                            return Exceptions.throwUnsafely(e);
                        }
                    }, ctx.eventLoop())));

            sb.service("/failingRequestPreviewSanitizer", httpService);
            sb.decorator("/failingRequestPreviewSanitizer",
                         ContentPreviewingService.builder(ContentPreviewerFactory.text(100))
                                                 .requestPreviewSanitizer(FAILING_CONTENT_SANITIZER)
                                                 .newDecorator());

            sb.service("/failingResponsePreviewSanitizer", httpService);
            sb.decorator("/failingResponsePreviewSanitizer",
                         ContentPreviewingService.builder(ContentPreviewerFactory.text(100))
                                                 .responsePreviewSanitizer(FAILING_CONTENT_SANITIZER)
                                                 .newDecorator());

            sb.decoratorUnder("/", (delegate, ctx, req) -> {
                contextCaptor.set(ctx);
                return delegate.serve(ctx, req);
            });
        }

        private Function<? super HttpService, ContentPreviewingService> decodingContentPreviewDecorator() {
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
            return ContentPreviewingService.newDecorator(factory);
        }
    };

    @BeforeEach
    void clear() {
        contextCaptor.set(null);
    }

    @Test
    void contentPreviewBeforeEncoding() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(DecodingClient.newDecorator())
                                          .build();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/beforeEncoding",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");
        assertThat(client.execute(headers, "Armeria").aggregate().join().contentUtf8())
                .isEqualTo("Hello Armeria!");
        final RequestLog requestLog = contextCaptor.get().log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
        assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    /**
     * Unlike {@link #contentPreviewBeforeEncoding()}, the content preview of this test is encoded data
     * because the previewing decorator is inserted after {@link EncodingService}.
     */
    @Test
    void encodedContentPreviewIsDecodedInPreviewer() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(DecodingClient.newDecorator())
                                          .build();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/encoded",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.execute(headers, "Armeria").aggregate().join();
            assertThat(res.contentUtf8()).isEqualTo("Hello Armeria!");
            final RequestLog requestLog = captor.get().log().whenComplete().join();
            assertThat(requestLog.responseHeaders().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo(
                    Brotli.isAvailable() ? "br" : "gzip");
        }

        final RequestLog requestLog = contextCaptor.get().log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
        assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    @Test
    void produceIsCalledWhenRequestCanceled() throws Exception {
        final HttpRequestWriter request = HttpRequest.streaming(RequestHeaders.of(HttpMethod.GET, "/"));
        request.write(HttpData.ofUtf8("foo"));
        final ContentPreviewer contentPreviewer = contentPreviewer();

        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        final HttpRequest filteredRequest = ContentPreviewingUtil.setUpRequestContentPreviewer(
                ctx, request, contentPreviewer, Functions.second());
        filteredRequest.subscribe(new CancelSubscriber());

        assertThat(ctx.log()
                      .whenAvailable(RequestLogProperty.REQUEST_CONTENT_PREVIEW)
                      .join()
                      .requestContentPreview()).isEqualTo("foo");
    }

    @Test
    void produceIsCalledWhenResponseCanceled() throws Exception {
        final HttpResponseWriter response = HttpResponse.streaming();
        response.write(ResponseHeaders.of(200));
        response.write(HttpData.ofUtf8("foo"));

        final ContentPreviewer contentPreviewer = contentPreviewer();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        final ContentPreviewerFactory factory = mock(ContentPreviewerFactory.class);
        when(factory.responseContentPreviewer(any(), any())).thenReturn(contentPreviewer);
        final HttpResponse filteredResponse = ContentPreviewingUtil.setUpResponseContentPreviewer(
                factory, ctx, response, Functions.second());
        filteredResponse.subscribe(new CancelSubscriber());

        assertThat(ctx.log()
                      .whenAvailable(RequestLogProperty.RESPONSE_CONTENT_PREVIEW)
                      .join()
                      .responseContentPreview()).isEqualTo("foo");
    }

    @Test
    void sanitizeRequestContentPreview() {
        final WebClient client = WebClient.of(server.httpUri());
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/requestPreviewSanitizer",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");
        assertThat(client.execute(headers, "Armeria").aggregate().join().contentUtf8())
                .isEqualTo("Hello Armeria!");
        final RequestLog requestLog = contextCaptor.get().log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("dummy content sanitizer");
        assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    @Test
    void sanitizeResponseContentPreview() {
        final WebClient client = WebClient.of(server.httpUri());
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/responsePreviewSanitizer",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");
        assertThat(client.execute(headers, "Armeria").aggregate().join().contentUtf8())
                .isEqualTo("Hello Armeria!");
        final RequestLog requestLog = contextCaptor.get().log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
        assertThat(requestLog.responseContentPreview()).isEqualTo("dummy content sanitizer");
    }

    @Test
    void sanitizeContentPreview() {
        final WebClient client = WebClient.of(server.httpUri());
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/previewSanitizer",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");
        assertThat(client.execute(headers, "Armeria").aggregate().join().contentUtf8())
                .isEqualTo("Hello Armeria!");
        final RequestLog requestLog = contextCaptor.get().log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("dummy content sanitizer");
        assertThat(requestLog.responseContentPreview()).isEqualTo("dummy content sanitizer");
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void deferredContentPreview(SessionProtocol protocol) {
        final WebClient client = WebClient.of(server.uri(protocol));
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/deferred",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");
        final AggregatedHttpResponse res = client.execute(headers, HttpData.ofUtf8("Armeria"))
                                                 .aggregate()
                                                 .join();
        assertThat(res.contentUtf8()).isEqualTo("Hello Armeria!");

        final RequestContext ctx = contextCaptor.get();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.requestContentPreview()).isEqualTo("Armeria");
        assertThat(log.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    @Test
    void failingRequestContentSanitizer() {
        final WebClient client = WebClient.of(server.httpUri());
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/failingRequestPreviewSanitizer",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");
        assertThat(client.execute(headers, "Armeria").aggregate().join().contentUtf8())
                .isEqualTo("Hello Armeria!");
        final RequestLog requestLog = contextCaptor.get().log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
        assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    @Test
    void failingResponseContentSanitizer() {
        final WebClient client = WebClient.of(server.httpUri());
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/failingResponsePreviewSanitizer",
                                                         HttpHeaderNames.CONTENT_TYPE, "text/plain");
        assertThat(client.execute(headers, "Armeria").aggregate().join().contentUtf8())
                .isEqualTo("Hello Armeria!");
        final RequestLog requestLog = contextCaptor.get().log().whenComplete().join();
        assertThat(requestLog.requestContentPreview()).isEqualTo("Armeria");
        assertThat(requestLog.responseContentPreview()).isEqualTo("Hello Armeria!");
    }

    private static ContentPreviewer contentPreviewer() {
        return new ContentPreviewer() {

            private HttpData data;

            @Override
            public void onData(HttpData data) {
                this.data = data;
            }

            @Override
            public String produce() {
                return data.toStringUtf8();
            }
        };
    }

    private static class CancelSubscriber implements Subscriber<HttpObject> {

        private Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpObject httpObject) {
            if (httpObject instanceof HttpData) {
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}
    }
}
