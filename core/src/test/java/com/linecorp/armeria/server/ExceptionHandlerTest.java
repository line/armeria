/*
 * Copyright 2021 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ExceptionHandlerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/timeout", (ctx, req) -> {
                ctx.timeoutNow();
                return HttpResponse.of(200);
            });
            sb.service("/throw-exception", (ctx, req) -> {
                ctx.logBuilder().defer(RequestLogProperty.RESPONSE_CONTENT);
                throw new IllegalArgumentException("Illegal Argument!");
            });
            sb.service("/responseSubscriber", (ctx, req) -> {
                final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                ctx.eventLoop().schedule(() -> future.completeExceptionally(
                        new UnsupportedOperationException("Unsupported!")), 100, TimeUnit.MILLISECONDS);
                return HttpResponse.from(future);
            });
            sb.exceptionHandler((ctx, cause) -> {
                if (cause instanceof RequestTimeoutException) {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.GATEWAY_TIMEOUT),
                                           HttpData.ofUtf8("timeout!"),
                                           HttpHeaders.of("trailer-exists", true));
                }
                if (cause instanceof IllegalArgumentException) {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.BAD_REQUEST),
                                           HttpData.ofUtf8(cause.getMessage()),
                                           HttpHeaders.of("trailer-exists", true));
                }
                if (cause instanceof UnsupportedOperationException) {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.NOT_IMPLEMENTED),
                                           HttpData.ofUtf8(cause.getMessage()),
                                           HttpHeaders.of("trailer-exists", true));
                }
                return null;
            });
        }
    };

    @Test
    void exceptionTranslated() {
        final WebClient client = WebClient.of(server.httpUri());
        AggregatedHttpResponse response = client.get("/timeout").aggregate().join();
        assertThat(response.headers().status()).isSameAs(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.contentUtf8()).isEqualTo("timeout!");
        assertThat(response.trailers().get("trailer-exists")).isEqualTo("true");

        response = client.get("/throw-exception").aggregate().join();
        assertThat(response.headers().status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).isEqualTo("Illegal Argument!");
        assertThat(response.trailers().get("trailer-exists")).isEqualTo("true");

        response = client.get("/responseSubscriber").aggregate().join();
        assertThat(response.headers().status()).isSameAs(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.contentUtf8()).isEqualTo("Unsupported!");
        assertThat(response.trailers().get("trailer-exists")).isEqualTo("true");
    }

    @Test
    void logIsCompleteEvenIfResponseContentIsDeferred() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        client.get("/throw-exception").aggregate().join();
        final ServiceRequestContext ctx = server.requestContextCaptor().poll();
        assertThat(ctx).isNotNull();
        await().until(() -> ctx.log().whenComplete().isDone());
    }

    @Test
    void exceptionHandlerOrElse() {
        final ExceptionHandler handler = new ExceptionHandler() {
            @Nullable
            @Override
            public HttpResponse convert(ServiceRequestContext context, Throwable cause) {
                if (cause instanceof AnticipatedException) {
                    return HttpResponse.of(200);
                }
                return null;
            }
        };

        final ExceptionHandler orElse = handler.orElse((ctx, cause) -> HttpResponse.of(400));

        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(orElse.convert(ctx, new AnticipatedException()).aggregate().join().status())
                .isSameAs(HttpStatus.OK);
        assertThat(orElse.convert(ctx, new IllegalStateException()).aggregate().join().status())
                .isSameAs(HttpStatus.BAD_REQUEST);
    }
}
