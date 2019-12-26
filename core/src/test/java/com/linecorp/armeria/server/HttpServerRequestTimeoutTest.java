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

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.streaming.JsonTextSequences;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import reactor.core.publisher.Flux;

class HttpServerRequestTimeoutTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(200)
              .service("/extend-timeout-from-now", (ctx, req) -> {
                  final Flux<Long> publisher =
                          Flux.interval(Duration.ofMillis(100))
                              .doOnNext(i -> ctx.resetRequestTimeout(Duration.ofMillis(150)));
                  return JsonTextSequences.fromPublisher(publisher.take(5));
              })
              .service("/extend-timeout-from-start", (ctx, req) -> {
                  final Flux<Long> publisher =
                          Flux.interval(Duration.ofMillis(100))
                              .doOnNext(i -> {
                                  ctx.adjustRequestTimeout(Duration.ofMillis(100));
                              });
                  return JsonTextSequences.fromPublisher(publisher.take(5));
              })
              .service("/timeout-while-writing", (ctx, req) -> {
                  final Flux<Long> publisher = Flux.interval(Duration.ofMillis(100));
                  return JsonTextSequences.fromPublisher(publisher.take(5));
              })
              .service("/timeout-before-writing", (ctx, req) -> {
                  final Flux<Long> publisher = Flux.interval(Duration.ofMillis(300));
                  return JsonTextSequences.fromPublisher(publisher.take(5));
              });
        }
    };

    WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.uri("/"));
    }

    @CsvSource({
            "/extend-timeout-from-now, 200",
            "/extend-timeout-from-start, 200",
    })
    @ParameterizedTest
    void extendRequestTimeout(String path, int status) {
        final AggregatedHttpResponse response = client.get(path).aggregate().join();
        assertThat(response.status().code()).isEqualTo(status);
    }

    @Test
    void requestTimeout_503() {
        final AggregatedHttpResponse response = client.get("/timeout-before-writing").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void requestTimeout_reset_stream() {
        assertThatThrownBy(() -> client.get("/timeout-while-writing").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClosedSessionException.class);
    }
}
