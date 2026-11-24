/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerDecision;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

class ArmeriaCallCircuitBreakerCancellationTest {

    @ParameterizedTest
    @CsvSource({ "false, false", "true, false", "true, true" })
    void cancellationReleasesContentWhileCircuitBreakerDecisionIsPending(boolean sourceCompleted,
                                                                         boolean callerCompleted)
            throws Exception {
        final ByteBuf content = ByteBufAllocator.DEFAULT.buffer().writeByte(1);
        final HttpResponseWriter source = HttpResponse.streaming();
        final CompletableFuture<CircuitBreakerDecision> decision = new CompletableFuture<>();
        final CompletableFuture<HttpResponse> ruleResponse = new CompletableFuture<>();
        final CompletableFuture<HttpResponse> callerResponse = new CompletableFuture<>();
        final CircuitBreakerRuleWithContent<HttpResponse> rule = (ctx, response, cause) -> {
            ruleResponse.complete(response);
            return decision;
        };
        final WebClient client = WebClient.builder("http://127.0.0.1:1")
                                          .responseTimeoutMillis(0)
                                          .decorator((delegate, ctx, req) -> {
                                              ctx.logBuilder().responseHeaders(
                                                      ResponseHeaders.of(HttpStatus.OK));
                                              return source;
                                          })
                                          .decorator(CircuitBreakerClient.newDecorator(
                                                  CircuitBreaker.ofDefaultName(), rule))
                                          .decorator((delegate, ctx, req) -> {
                                              final HttpResponse response = delegate.execute(ctx, req);
                                              callerResponse.complete(response);
                                              return response;
                                          })
                                          .build();
        final Call call = ArmeriaRetrofit.builder(client).streaming(true).build().callFactory()
                                        .newCall(new Request.Builder().url("http://127.0.0.1:1/").build());
        source.write(ResponseHeaders.of(HttpStatus.OK));
        source.write(HttpData.wrap(content));
        if (sourceCompleted) {
            source.close();
        }
        try (Response response = call.execute()) {
            final HttpResponse retained = ruleResponse.get(5, TimeUnit.SECONDS);
            if (callerCompleted) {
                assertThat(response.body().source().readByte()).isEqualTo((byte) 1);
                assertThat(response.body().source().exhausted()).isTrue();
                callerResponse.get(5, TimeUnit.SECONDS).whenComplete().get(5, TimeUnit.SECONDS);
            }
            if (!sourceCompleted) {
                assertThat(callerResponse.get(5, TimeUnit.SECONDS).whenComplete()).isNotDone();
            }
            assertThat(content.refCnt()).isPositive();
            assertThat(decision).isNotDone();
            assertThat(retained.whenComplete()).isNotDone();

            call.cancel();

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(content.refCnt()).isZero());
            assertThat(call.isCanceled()).isTrue();
            assertThat(decision).isNotDone();
        } finally {
            call.cancel();
            decision.complete(CircuitBreakerDecision.success());
            source.abort();
        }
    }
}
