/*
 * Copyright 2016 LINE Corporation
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
import static org.assertj.core.api.Assertions.catchException;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.EmptyHttpResponseException;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(
                    "/hello/{name}",
                    new AbstractHttpService() {
                        @Override
                        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                            final String name = ctx.pathParam("name");
                            return HttpResponse.of(
                                    HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s!", name);
                        }
                    }.decorate(LoggingService.newDecorator()));
            sb.service("/trailersWithoutData", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                           HttpHeaders.of(HttpHeaderNames.of("foo"), "bar"));
                }
            });
            sb.service("/dataAndTrailers", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                           HttpData.ofUtf8("trailer"),
                                           HttpHeaders.of(HttpHeaderNames.of("foo"), "bar"));
                }
            });
            sb.service("/additionalTrailers", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    ctx.mutateAdditionalResponseTrailers(
                            mutator -> mutator.add(HttpHeaderNames.of("foo"), "baz"));
                    return HttpResponse.of(HttpStatus.OK);
                }
            });

            sb.service(
                    "/200",
                    new AbstractHttpService() {
                        @Override
                        protected HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) {
                            return HttpResponse.of(HttpStatus.OK);
                        }

                        @Override
                        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                            return HttpResponse.of(HttpStatus.OK);
                        }
                    }.decorate(LoggingService.newDecorator()));

            sb.service(
                    "/204",
                    new AbstractHttpService() {
                        @Override
                        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                            return HttpResponse.of(HttpStatus.NO_CONTENT);
                        }
                    }.decorate(LoggingService.newDecorator()));
            sb.service("/empty/BIDI_STREAMING", emptyService(ExchangeType.BIDI_STREAMING));
            sb.service("/empty/UNARY", emptyService(ExchangeType.UNARY));
        }
    };

    private static HttpService emptyService(ExchangeType exchangeType) {
        return new HttpService() {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                final HttpResponseWriter writer = HttpResponse.streaming();
                writer.close();
                return writer;
            }

            @Override
            public ExchangeType exchangeType(RoutingContext routingContext) {
                return exchangeType;
            }
        };
    }

    @Test
    void testHello() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/hello/foo"))) {
                assertThat(res.getCode()).isEqualTo(200);
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("Hello, foo!");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/hello/foo/bar"))) {
                assertThat(res.getCode()).isEqualTo(404);
            }

            try (CloseableHttpResponse res = hc.execute(new HttpDelete(server.httpUri() + "/hello/bar"))) {
                assertThat(res.getCode()).isEqualTo(405);
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo(
                        "405 Method Not Allowed");
            }
        }
    }

    @Test
    void testContentLength() throws Exception {
        // Test if the server responds with the 'content-length' header
        // even if it is the last response of the connection.
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(server.httpUri() + "/200");
            req.setHeader("Connection", "Close");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getCode()).isEqualTo(200);
                assertThat(res.containsHeader("Content-Length")).isTrue();
                assertThat(res.getHeaders("Content-Length"))
                        .extracting(Header::getValue).containsExactly("6");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("200 OK");
            }
        }

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Ensure the HEAD response does not have content.
            try (CloseableHttpResponse res = hc.execute(new HttpHead(server.httpUri() + "/200"))) {
                assertThat(res.getCode()).isEqualTo(200);
                assertThat(res.getEntity()).isNull();
            }

            // Ensure the 204 response does not have content.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/204"))) {
                assertThat(res.getCode()).isEqualTo(204);
                assertThat(res.getEntity()).isNull();
            }
        }
    }

    @Test
    void contentLengthIsNotSetWhenTrailerExists() {
        final WebClient client = WebClient.of(server.httpUri());
        AggregatedHttpResponse res = client.get("/trailersWithoutData").aggregate().join();
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
        assertThat(res.trailers().get(HttpHeaderNames.of("foo"))).isEqualTo("bar");
        assertThat(res.content()).isSameAs(HttpData.empty());

        res = client.get("/dataAndTrailers").aggregate().join();
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
        assertThat(res.trailers().get(HttpHeaderNames.of("foo"))).isEqualTo("bar");
        assertThat(res.contentUtf8()).isEqualTo("trailer");

        res = client.get("/additionalTrailers").aggregate().join();
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
        assertThat(res.trailers().get(HttpHeaderNames.of("foo"))).isEqualTo("baz");
    }

    @ParameterizedTest
    @CsvSource({"H1C,UNARY", "H2C,UNARY", "H1C,BIDI_STREAMING", "H2C,BIDI_STREAMING"})
    void emptyResponseCompletes(SessionProtocol sessionProtocol, ExchangeType exchangeType) throws Exception {
        // an exception is thrown since the server closed the connection without sending any data
        // which violates the http protocol
        final Exception exception = catchException(
                () -> WebClient.builder(sessionProtocol, server.httpEndpoint()).build()
                               .blocking().get("/empty/" + exchangeType));
        if (sessionProtocol.isMultiplex()) {
            assertThat(exception).isInstanceOf(ClosedStreamException.class);
        } else {
            assertThat(exception).isInstanceOf(ClosedSessionException.class);
        }
        assertThat(server.requestContextCaptor().size()).isEqualTo(1);
        final ServiceRequestContext sctx = server.requestContextCaptor().poll();
        await().atMost(10, TimeUnit.SECONDS).until(() -> sctx.log().isComplete());
        assertThat(sctx.log().ensureComplete().responseCause())
                .isInstanceOf(EmptyHttpResponseException.class);
    }
}
