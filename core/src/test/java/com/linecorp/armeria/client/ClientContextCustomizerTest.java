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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class ClientContextCustomizerTest {

    private static final AttributeKey<String> TRACE_ID =
            AttributeKey.valueOf(ClientContextCustomizerTest.class, "TRACE_ID");

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/foo", (ctx, req) -> {
                return HttpResponse.of(req.headers().get("X-Trace-ID") + ':' + req.headers().get("User-ID"));
            });
        }
    };

    public static Stream<Arguments> contextCustomizer_ClientBuilder_args() {
        final HttpPreprocessor asyncPreprocessor =
                (delegate, ctx, req) -> HttpResponse.of(
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return delegate.execute(ctx, req);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }));
        return Stream.of(
                Arguments.of(WebClient.builder(server.httpUri())),
                Arguments.of(WebClient.builder(server.httpUri())
                                      .preprocessor(asyncPreprocessor))
        );
    }

    @ParameterizedTest
    @MethodSource("contextCustomizer_ClientBuilder_args")
    void contextCustomizer_ClientBuilder(WebClientBuilder builder) {
        final String traceId = "12345";
        final AtomicReference<Thread> threadRef = new AtomicReference<>();

        final BlockingWebClient client =
                builder.contextCustomizer(ctx -> {
                           threadRef.set(Thread.currentThread());
                           ctx.setAttr(TRACE_ID, traceId);
                       })
                       .decorator((delegate, ctx, req) -> {
                           final HttpRequest newReq = req.mapHeaders(headers -> {
                               return headers.toBuilder()
                                             .add("X-Trace-ID", ctx.attr(TRACE_ID))
                                             .build();
                           });
                           ctx.updateRequest(newReq);
                           return delegate.execute(ctx, newReq);
                       }).build()
                       .blocking();

        assertThat(client.get("/foo").contentUtf8()).isEqualTo("12345:null");
        assertThat(threadRef).hasValue(Thread.currentThread());
    }

    @Test
    void multipleContextCustomizer_ClientBuilder() {
        final List<Integer> executionOrders = new ArrayList<>();
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .contextCustomizer(ctx -> {
                             executionOrders.add(1);
                             ctx.addAdditionalRequestHeader("X-Trace-ID", "12345");
                         })
                         .contextCustomizer(ctx -> {
                             executionOrders.add(2);
                             ctx.addAdditionalRequestHeader("User-ID", "2m");
                         })
                         .build()
                         .blocking();

        assertThat(client.get("/foo").contentUtf8()).isEqualTo("12345:2m");
        assertThat(executionOrders).containsExactly(1, 2);
    }

    @Test
    void contextCustomizer_withThreadLocalContext() {
        final List<Integer> executionOrders = new ArrayList<>();
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .contextCustomizer(ctx -> {
                             executionOrders.add(1);
                             ctx.addAdditionalRequestHeader("X-Trace-ID", "12345");
                         })
                         .build()
                         .blocking();

        try (SafeCloseable ignored = Clients.withContextCustomizer(ctx -> {
            executionOrders.add(2);
            ctx.addAdditionalRequestHeader("User-ID", "2m");
        })) {
            assertThat(client.get("/foo").contentUtf8()).isEqualTo("12345:2m");
        }
        assertThat(executionOrders).containsExactly(1, 2);
    }
}
