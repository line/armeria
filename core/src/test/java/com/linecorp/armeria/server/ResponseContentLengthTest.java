/*
 * Copyright 2022 LINE Corporation
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

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ResponseContentLengthTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/unary", new HttpService() {
                @Override
                public ExchangeType exchangeType(RequestHeaders headers, Route route) {
                    return ExchangeType.UNARY;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.builder()
                                       .status(HttpStatus.OK)
                                       .content("hello")
                                       .build();
                }
            });

            sb.service("/unary-trailers", new HttpService() {
                @Override
                public ExchangeType exchangeType(RequestHeaders headers, Route route) {
                    return ExchangeType.UNARY;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.builder()
                                       .status(HttpStatus.OK)
                                       .content("hello")
                                       .trailers(HttpHeaders.of("foo", "bar"))
                                       .build();
                }
            });

            sb.service("/streaming", new HttpService() {
                @Override
                public ExchangeType exchangeType(RequestHeaders headers, Route route) {
                    return ExchangeType.RESPONSE_STREAMING;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.builder()
                                       .status(HttpStatus.OK)
                                       .content("hello")
                                       .build();
                }
            });

            sb.service("/streaming-trailers", new HttpService() {
                @Override
                public ExchangeType exchangeType(RequestHeaders headers, Route route) {
                    return ExchangeType.RESPONSE_STREAMING;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.builder()
                                       .status(HttpStatus.OK)
                                       .content("hello")
                                       .trailers(HttpHeaders.of("foo", "bar"))
                                       .build();
                }
            });
        }
    };

    @CsvSource({ "/unary, H1C", "/unary, H2C", "/streaming, H1C", "/streaming, H2C" })
    @ParameterizedTest
    void withoutTrailers(String path, SessionProtocol protocol) throws InterruptedException {
        final WebClient client = WebClient.of(server.uri(protocol));
        final AggregatedHttpResponse response = client.get(path).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("hello");
        assertThat(response.trailers()).isEmpty();
        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.responseHeaders().contentLength()).isEqualTo(response.content().length());
    }

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void unaryWithTrailers(SessionProtocol protocol) throws InterruptedException {
        final WebClient client = WebClient.of(server.uri(protocol));
        final AggregatedHttpResponse response = client.get("/unary-trailers").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("hello");
        assertThat(response.trailers()).hasSize(1);
        assertThat(response.trailers().get("foo")).isEqualTo("bar");
        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog log = ctx.log().whenComplete().join();
        if (protocol.isMultiplex()) {
            // In HTTP/2, a non-streaming response always has a content-length.
            assertThat(log.responseHeaders().contentLength()).isEqualTo(response.content().length());
        } else {
            assertThat(log.responseHeaders().contentLength()).isEqualTo(-1);
        }
    }

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void streamingWithTrailers(SessionProtocol protocol) throws InterruptedException {
        final WebClient client = WebClient.of(server.uri(protocol));
        final AggregatedHttpResponse response = client.get("/streaming-trailers").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.trailers().get("foo")).isEqualTo("bar");
        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.responseHeaders().contentLength()).isEqualTo(-1);
    }
}
