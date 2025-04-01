/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DecoratorResponseLogCompletionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @EnumSource(ResponseTimeoutMode.class)
    @ParameterizedTest
    void testSimple_success(ResponseTimeoutMode timeoutMode) {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             return HttpResponse.of(HttpStatus.OK);
                         })
                         .responseTimeoutMode(timeoutMode)
                         .build()
                         .blocking();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse response = client.get("/");
            final ClientRequestContext ctx = captor.get();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            // Make sure that the log is completed.
            final RequestLog log = ctx.log().whenComplete().join();
            // The headers are not logged.
            assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.UNKNOWN);
            assertThat(ctx.whenResponseCancelled().join())
                    .isInstanceOf(ResponseCompleteException.class);
        }
    }

    @EnumSource(ResponseTimeoutMode.class)
    @ParameterizedTest
    void testSimple_failure(ResponseTimeoutMode timeoutMode) {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             return HttpResponse.ofFailure(new AnticipatedException());
                         })
                         .responseTimeoutMode(timeoutMode)
                         .build()
                         .blocking();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(AnticipatedException.class);
            final ClientRequestContext ctx = captor.get();
            final RequestLog log = ctx.log().whenComplete().join();
            assertThat(log.responseCause()).isInstanceOf(AnticipatedException.class);
            assertThat(ctx.whenResponseCancelled().join())
                    .isInstanceOf(AnticipatedException.class);
        }
    }

    @EnumSource(ExchangeType.class)
    @ParameterizedTest
    void testRetryingClient_success(ExchangeType exchangeType) {
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             return HttpResponse.of(HttpStatus.OK);
                         })
                         .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
                         .build();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse response =
                    client.prepare()
                          .get("/")
                          .exchangeType(exchangeType)
                          .execute()
                          .aggregate()
                          .join();
            final ClientRequestContext ctx = captor.get();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            // Make sure that the log is completed.
            final RequestLog log = ctx.log().whenComplete().join();
            assertThat(log.children().size()).isEqualTo(1);
            // RetryingClient logs the headers
            assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.OK);
        }
    }

    @CsvSource({ "UNARY, true", "UNARY, false", "BIDI_STREAMING, true", "BIDI_STREAMING, false" })
    @ParameterizedTest
    void testRetryingClient_responseFailure(ExchangeType exchangeType, boolean throwException) {
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             if (throwException) {
                                 throw new AnticipatedException();
                             } else {
                                 return HttpResponse.ofFailure(new AnticipatedException());
                             }
                         })
                         .decorator(RetryingClient.builder(RetryRule.failsafe())
                                                  .maxTotalAttempts(2)
                                                  .newDecorator())
                         .build();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> {
                client.prepare()
                      .get("/")
                      .exchangeType(exchangeType)
                      .execute()
                      .aggregate()
                      .join();
            }).isInstanceOf(CompletionException.class)
              .hasCauseInstanceOf(AnticipatedException.class);
            final ClientRequestContext ctx = captor.get();
            // Make sure that the log is completed.
            final RequestLog log = ctx.log().whenComplete().join();
            assertThat(log.responseCause()).isInstanceOf(AnticipatedException.class);
            assertThat(log.children().size()).isEqualTo(2);
        }
    }
}
