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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpClientResponseTimeoutHandlerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(final ServerBuilder sb) throws Exception {
            sb.service("/slow", (ctx, req) -> {
                final HttpResponseWriter response = HttpResponse.streaming();
                response.write(ResponseHeaders.of(HttpStatus.OK));
                response.write(HttpData.ofUtf8("slow response"));
                return response;
            });
        }
    };

    @ParameterizedTest
    @CsvSource({
            "H1C, true", "H1C, false",
            "H2C, true", "H2C, false"
    })
    void testResponseTimeoutHandler(SessionProtocol protocol, boolean useResponseTimeoutHandler) {
        final AtomicReference<RequestLogAccess> logHolder = new AtomicReference<>();
        final IllegalStateException reqCause = new IllegalStateException("abort request");
        final AtomicBoolean invokeResponseTimeoutHandler = new AtomicBoolean(false);
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .responseTimeout(Duration.ofSeconds(2))
                                          .decorator((delegate, ctx, req) -> {
                                              if (useResponseTimeoutHandler) {
                                                  ctx.whenResponseTimingOut().thenRun(() -> {
                                                      ctx.request().abort(reqCause);
                                                      invokeResponseTimeoutHandler.set(true);
                                                  });
                                              }
                                              logHolder.set(ctx.log());
                                              return delegate.execute(ctx, req);
                                          })
                                          .build();

        final HttpRequestWriter writer = HttpRequest.streaming(HttpMethod.POST, "/slow");
        final HttpResponse response = client.execute(writer);
        final RequestLog log = logHolder.get().whenComplete().join();

        if (useResponseTimeoutHandler) {
            await().untilTrue(invokeResponseTimeoutHandler);
            assertThat(log.requestCause()).isSameAs(reqCause);
            assertThatThrownBy(() -> response.aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseReference(reqCause);
        } else {
            assertThat(log.requestCause()).isInstanceOf(ResponseTimeoutException.class);
            assertThatThrownBy(() -> response.aggregate().join())
                    .hasCauseInstanceOf(ResponseTimeoutException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testResponseTimeoutHandlerRequestAbort(SessionProtocol protocol) {
        final AtomicReference<RequestLogAccess> logHolder = new AtomicReference<>();
        final IllegalStateException reqCause = new IllegalStateException("abort request");
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .responseTimeout(Duration.ofSeconds(2))
                                          .decorator((delegate, ctx, req) -> {
                                              ctx.whenResponseTimingOut().thenRun(() -> {
                                                  ctx.request().abort(reqCause);
                                              });
                                              logHolder.set(ctx.log());
                                              return delegate.execute(ctx, req);
                                          })
                                          .build();

        final HttpRequestWriter writer = HttpRequest.streaming(HttpMethod.POST, "/slow");
        final HttpResponse response = client.execute(writer);
        final RequestLog log = logHolder.get().whenComplete().join();

        assertThatThrownBy(() -> response.aggregate().join()).isInstanceOf(CompletionException.class)
                                                             .hasCauseReference(reqCause);
        assertThat(log.requestCause()).isSameAs(reqCause);
        assertThat(log.responseCause()).isSameAs(reqCause);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testResponseTimeoutHandlerResponseAbort(SessionProtocol protocol) {
        final AtomicReference<RequestLogAccess> logHolder = new AtomicReference<>();
        final IllegalStateException resCause = new IllegalStateException("abort response");
        final AtomicBoolean invokeResponseTimeoutHandler = new AtomicBoolean(false);
        final WebClient client = WebClient
                .builder(server.uri(protocol))
                .responseTimeout(Duration.ofSeconds(2))
                .decorator((delegate, ctx, req) -> {
                    final HttpResponse response = delegate.execute(ctx, req);
                    ctx.whenResponseTimingOut().thenRun(() -> {
                        invokeResponseTimeoutHandler.set(true);
                        response.abort(resCause);
                    });
                    logHolder.set(ctx.log());
                    return response;
                })
                .build();

        final HttpRequestWriter writer = HttpRequest.streaming(HttpMethod.POST, "/slow");
        final HttpResponse response = client.execute(writer);
        final RequestLog log = logHolder.get().whenComplete().join();

        assertThat(invokeResponseTimeoutHandler).isTrue();
        assertThatThrownBy(() -> response.aggregate().join()).isInstanceOf(CompletionException.class)
                                                             .hasCause(resCause);
        assertThat(log.requestCause()).isSameAs(resCause);
        assertThat(log.responseCause()).isSameAs(resCause);
    }
}
