/*
 * Copyright 2019 LINE Corporation
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

import java.time.Duration;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.server.streaming.JsonTextSequences;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;

class HttpServerRequestTimeoutTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(400)
              .service("/extend-timeout-from-now", (ctx, req) -> {
                  final Flux<Long> publisher =
                          Flux.interval(Duration.ofMillis(200))
                              .doOnNext(i -> ctx.setRequestTimeout(TimeoutMode.SET_FROM_NOW,
                                                                   Duration.ofMillis(300)));
                  return JsonTextSequences.fromPublisher(publisher.take(5));
              })
              .service("/extend-timeout-from-start", (ctx, req) -> {
                  final Flux<Long> publisher =
                          Flux.interval(Duration.ofMillis(200))
                              .doOnNext(i -> ctx.setRequestTimeout(TimeoutMode.EXTEND, Duration.ofMillis(200)));
                  return JsonTextSequences.fromPublisher(publisher.take(5));
              })
              .service("/timeout-while-writing", (ctx, req) -> {
                  final Flux<Long> publisher = Flux.interval(Duration.ofMillis(200));
                  return JsonTextSequences.fromPublisher(publisher.take(5));
              })
              .service("/timeout-before-writing", (ctx, req) -> {
                  final Flux<Long> publisher = Flux.interval(Duration.ofMillis(800));
                  return JsonTextSequences.fromPublisher(publisher.take(5));
              })
              .service("/timeout-immediately", (ctx, req) -> {
                  ctx.timeoutNow();
                  return HttpResponse.delayed(HttpResponse.of(200), Duration.ofSeconds(1));
              })
              .serviceUnder("/timeout-by-decorator", (ctx, req) ->
                      HttpResponse.delayed(HttpResponse.of(200), Duration.ofSeconds(1)))
              .decorator("/timeout-by-decorator/extend", (delegate, ctx, req) -> {
                  ctx.setRequestTimeout(TimeoutMode.EXTEND, Duration.ofSeconds(2));
                  return delegate.serve(ctx, req);
              })
              .decorator("/timeout-by-decorator/deadline", (delegate, ctx, req) -> {
                  ctx.setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofSeconds(2));
                  return delegate.serve(ctx, req);
              })
              .decorator("/timeout-by-decorator/clear", (delegate, ctx, req) -> {
                  ctx.clearRequestTimeout();
                  return delegate.serve(ctx, req);
              })
              .decorator("/timeout-by-decorator/from_now", (delegate, ctx, req) -> {
                  ctx.setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofSeconds(2));
                  return delegate.serve(ctx, req);
              });
        }
    };

    @RegisterExtension
    static ServerExtension serverWithoutTimeout = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(0)
              .service("/extend-timeout-from-now", (ctx, req) -> {
                  final Flux<Long> publisher =
                          Flux.interval(Duration.ofMillis(100))
                              .doOnNext(i -> ctx.setRequestTimeout(TimeoutMode.SET_FROM_NOW,
                                                                   Duration.ofMillis(150)));
                  return JsonTextSequences.fromPublisher(publisher.take(5));
              })
              .service("/timeout-now", (ctx, req) -> {
                  ctx.timeoutNow();
                  return HttpResponse.delayed(HttpResponse.of(200), Duration.ofSeconds(1));
              })
              .serviceUnder("/timeout-by-decorator", (ctx, req) -> HttpResponse.streaming())
              .decorator("/timeout-by-decorator/from_now", (delegate, ctx, req) -> {
                  ctx.setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofSeconds(1));
                  return delegate.serve(ctx, req);
              })
              .decorator("/timeout-by-decorator/from_start", (delegate, ctx, req) -> {
                  ctx.setRequestTimeout(TimeoutMode.SET_FROM_START, Duration.ofSeconds(1));
                  assertThat(ctx.requestTimeoutMillis()).isEqualTo(1000);
                  return delegate.serve(ctx, req);
              });
        }
    };

    WebClient client;
    WebClient withoutTimeoutServerClient;

    @BeforeEach
    void setUp() {
        client = WebClient.builder(server.httpUri())
                          .option(ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(0L))
                          .build();
        withoutTimeoutServerClient = WebClient.builder(serverWithoutTimeout.httpUri())
                                              .option(ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(0L))
                                              .build();
    }

    @ParameterizedTest
    @CsvSource({
            "/extend-timeout-from-now, 200",
            "/extend-timeout-from-start, 200",
    })
    void setRequestTimeoutAfter(String path, int status) {
        final AggregatedHttpResponse response = client.get(path).aggregate().join();
        assertThat(response.status().code()).isEqualTo(status);
    }

    @Test
    void requestTimeout_503() {
        final AggregatedHttpResponse response =
                client.get("/timeout-before-writing").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void requestTimeout_reset_stream() {
        assertThatThrownBy(() -> client.get("/timeout-while-writing").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClosedStreamException.class);
    }

    @Test
    void setRequestTimeoutAfterNoTimeout() {
        final AggregatedHttpResponse response = withoutTimeoutServerClient.get(
                "/extend-timeout-from-now").aggregate().join();
        assertThat(response.status().code()).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource({
            "/timeout-by-decorator/extend",
            "/timeout-by-decorator/deadline",
            "/timeout-by-decorator/clear",
            "/timeout-by-decorator/from_now",
    })
    void extendRequestTimeoutByDecorator(String path) {
        final AggregatedHttpResponse response =
                client.get(path).aggregate().join();
        assertThat(response.status().code()).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource({
            "/timeout-by-decorator/from_now",
            "/timeout-by-decorator/from_start",
    })
    void limitRequestTimeoutByDecorator(String path) {
        final AggregatedHttpResponse response =
                withoutTimeoutServerClient.get(path).aggregate().join();
        assertThat(response.status().code()).isEqualTo(503);
    }

    @Test
    void timeoutNow() {
        final AggregatedHttpResponse response =
                withoutTimeoutServerClient.get("/timeout-now").aggregate().join();
        assertThat(response.status().code()).isEqualTo(503);
    }
}
