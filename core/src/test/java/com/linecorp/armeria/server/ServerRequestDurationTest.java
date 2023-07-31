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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServerRequestDurationTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(100_000);
            sb.service("/slow-request", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    if (routingContext.params().contains("streaming")) {
                        return ExchangeType.REQUEST_STREAMING;
                    } else {
                        return ExchangeType.UNARY;
                    }
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(req.aggregate().thenApply(agg -> {
                        return HttpResponse.of(agg.contentUtf8());
                    }));
                }
            });
            sb.service("/slow-response", new HttpService() {

                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    if (routingContext.params().contains("streaming")) {
                        return ExchangeType.RESPONSE_STREAMING;
                    } else {
                        return ExchangeType.UNARY;
                    }
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(req.aggregate().thenApply(agg -> {
                        final HttpResponseWriter writer = HttpResponse.streaming();
                        writer.write(ResponseHeaders.of(HttpStatus.OK));
                        writer.write(HttpData.ofUtf8("12"));
                        ctx.eventLoop().schedule(() -> {
                            writer.write(HttpData.ofUtf8("34"));
                            writer.close();
                        }, 5000, TimeUnit.MILLISECONDS);
                        return writer;
                    }));
                }
            });
        }
    };

    @CsvSource({"/slow-request", "/slow-request?streaming=true"})
    @ParameterizedTest
    void requestDuration(String path) throws InterruptedException {
        final WebClient client = server.webClient(cb -> cb.responseTimeoutMillis(100_000));
        final StreamWriter<HttpData> stream = StreamMessage.streaming();
        final CompletableFuture<ResponseEntity<String>> future =
                client.prepare()
                      .post(path)
                      .content(MediaType.PLAIN_TEXT, stream)
                      .asString()
                      .execute();

        stream.write(HttpData.ofUtf8("12"));
        Thread.sleep(5000);
        stream.write(HttpData.ofUtf8("34"));
        stream.close();

        final ResponseEntity<String> response = future.join();
        assertThat(response.content()).isEqualTo("1234");
        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(TimeUnit.NANOSECONDS.toMillis(log.requestDurationNanos()))
                .isGreaterThanOrEqualTo(4000);
    }

    @CsvSource({"/slow-response", "/slow-response?streaming=true"})
    @ParameterizedTest
    void responseDuration(String path) throws InterruptedException {
        final WebClient client = server.webClient(cb -> cb.responseTimeoutMillis(100_000));
        final CompletableFuture<ResponseEntity<String>> future =
                client.prepare()
                      .get(path)
                      .asString()
                      .execute();

        final ResponseEntity<String> response = future.join();
        assertThat(response.content()).isEqualTo("1234");
        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog log = ctx.log().whenComplete().join();
        if (path.contains("streaming")) {
            assertThat(TimeUnit.NANOSECONDS.toMillis(log.responseDurationNanos()))
                    .isGreaterThanOrEqualTo(4000);
        } else {
            // The headers and body are written together after the stream is closed.
            assertThat(TimeUnit.NANOSECONDS.toMillis(log.responseDurationNanos()))
                    .isLessThanOrEqualTo(1000);
        }
    }
}
