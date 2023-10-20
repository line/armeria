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

package com.linecorp.armeria.client.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.Maps;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.common.InternalGrpcWebTrailers;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class CircuitBreakerClientRuleTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of("Hello, Armeria!"));
            sb.service("/1-second-sleep", (ctx, req) -> HttpResponse
                    .delayed(HttpResponse.of("Hello, Armeria!"), Duration.ofSeconds(1)));
            sb.service("/503", (ctx, req) -> HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE));
            sb.service("/slow", (ctx, req) -> HttpResponse.streaming());
            sb.service("/trailers", (ctx, req) -> HttpResponse.of(ResponseHeaders.of(200),
                                                                  HttpData.ofUtf8("oops"),
                                                                  HttpHeaders.of("x-checksum", 42)));
            sb.service("/grpc", (ctx, req) -> HttpResponse.of(ResponseHeaders.of(200),
                                                              HttpData.ofUtf8("oops"),
                                                              HttpHeaders.of("grpc-status", 3)));
            sb.service("/grpc-trailers-only", (ctx, req) -> {
                return HttpResponse.of(ResponseHeaders.builder(200)
                                                      .addInt("grpc-status", 7)
                                                      .build());
            });
            sb.service("/grpc-web", (ctx, req) -> {
                // We call InternalGrpcWebTrailers.set(...) to emulate a gRPC Web response,
                // so we don't really need to do anything on the server side.
                return HttpResponse.of(200);
            });
        }
    };

    @Test
    void openCircuitWithContent() {
        final CircuitBreakerRuleWithContent<HttpResponse> rule =
                CircuitBreakerRuleWithContent
                        .<HttpResponse>builder()
                        .onResponse((unused, response) -> {
                            return response.aggregate()
                                           .thenApply(content -> content.contentUtf8().contains("Hello"));
                        })
                        .thenFailure();

        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(CircuitBreakerClient.newDecorator(superSensitiveCircuitBreaker(), rule))
                         .build()
                         .blocking();

        assertThat(client.get("/").contentUtf8()).isEqualTo("Hello, Armeria!");
        await().untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/")).isInstanceOf(FailFastException.class);
        });
    }

    @Test
    void openCircuitWithStatus() {
        final CircuitBreakerRule rule = CircuitBreakerRule.onStatus(HttpStatus.SERVICE_UNAVAILABLE);

        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(CircuitBreakerClient.newDecorator(superSensitiveCircuitBreaker(), rule))
                         .build();

        assertThat(client.get("/503").aggregate().join().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        await().untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/503").aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(FailFastException.class);
        });
    }

    @Test
    void openCircuitWithTrailer() {
        final CircuitBreakerRule rule =
                CircuitBreakerRule.builder()
                                  .onResponseTrailers((ctx, trailers) -> trailers.containsInt("x-checksum", 42))
                                  .thenFailure();

        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(CircuitBreakerClient.newDecorator(superSensitiveCircuitBreaker(), rule))
                         .build();

        assertThat(client.get("/trailers").aggregate().join().trailers()).containsExactly(
                Maps.immutableEntry(HttpHeaderNames.of("x-checksum"), "42"));
        await().untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/trailers").aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(FailFastException.class);
        });
    }

    @Test
    void openCircuitWithGrpc() {
        final CircuitBreakerRule rule =
                CircuitBreakerRule.builder()
                                  .onGrpcTrailers((ctx, trailers) -> trailers.containsInt("grpc-status", 3))
                                  .thenFailure();

        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(CircuitBreakerClient.newDecorator(superSensitiveCircuitBreaker(), rule))
                         .build()
                         .blocking();

        final AggregatedHttpResponse res = client.get("/grpc");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.trailers()).containsExactly(
                Maps.immutableEntry(HttpHeaderNames.of("grpc-status"), "3"));
        await().untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/grpc"))
                    .isInstanceOf(FailFastException.class);
        });
    }

    @Test
    void openCircuitWithGrpcTrailersOnly() {
        final CircuitBreakerRule rule =
                CircuitBreakerRule.builder()
                                  .onGrpcTrailers((ctx, trailers) -> trailers.containsInt("grpc-status", 7))
                                  .thenFailure();

        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(CircuitBreakerClient.newDecorator(superSensitiveCircuitBreaker(), rule))
                         .build()
                         .blocking();

        final AggregatedHttpResponse res = client.get("/grpc-trailers-only");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers()).contains(Maps.immutableEntry(HttpHeaderNames.of("grpc-status"), "7"));
        await().untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/grpc-trailers-only"))
                    .isInstanceOf(FailFastException.class);
        });
    }

    @Test
    void openCircuitWithGrpcWeb() {
        final CircuitBreakerRule rule =
                CircuitBreakerRule.builder()
                                  .onGrpcTrailers((ctx, trailers) -> trailers.containsInt("grpc-status", 11))
                                  .thenFailure();

        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(CircuitBreakerClient.newDecorator(superSensitiveCircuitBreaker(), rule))
                         .decorator((delegate, ctx, req) -> {
                             InternalGrpcWebTrailers.set(ctx, HttpHeaders.of("grpc-status", 11));
                             return delegate.execute(ctx, req);
                         })
                         .build()
                         .blocking();

        final AggregatedHttpResponse res = client.get("/grpc-web");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().contains("grpc-status")).isFalse();
        assertThat(res.trailers().contains("grpc-status")).isFalse();

        await().untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/grpc-web"))
                    .isInstanceOf(FailFastException.class);
        });
    }

    @Test
    void openCircuitWithException() {
        final CircuitBreakerRule rule = CircuitBreakerRule.onException(ResponseTimeoutException.class);
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(CircuitBreakerClient.newDecorator(superSensitiveCircuitBreaker(), rule))
                         .responseTimeoutMillis(2000)
                         .build();

        assertThatThrownBy(() -> client.get("/slow").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        await().untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/slow").aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(FailFastException.class);
        });
    }

    @Test
    void openCircuitWithTotalDuration() {
        final CircuitBreakerRuleWithContent<HttpResponse> rule =
                CircuitBreakerRuleWithContent
                        .<HttpResponse>builder()
                        .onTotalDuration((ctx, duration) -> duration.toNanos() > 100)
                        .thenFailure();
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator(CircuitBreakerClient.newDecorator(CircuitBreaker.ofDefaultName(), rule))
                         .build()
                         .blocking();

        assertThat(client.get("/1-second-sleep").contentUtf8()).isEqualTo("Hello, Armeria!");
        await().untilAsserted(() -> {
            assertThatThrownBy(() -> client.get("/")).isInstanceOf(FailFastException.class);
        });
    }

    private static CircuitBreaker superSensitiveCircuitBreaker() {
        return CircuitBreaker.builder().minimumRequestThreshold(0).build();
    }
}
