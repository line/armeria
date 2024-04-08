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
 * under the License
 */

package com.linecorp.armeria.server.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.brave.BraveClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import brave.Tracing;
import brave.baggage.BaggageFields;
import brave.context.slf4j.MDCScopeDecorator;
import brave.propagation.CurrentTraceContext;

class SpanPropagationTest {

    private static final CurrentTraceContext traceCtx =
            RequestContextCurrentTraceContext.builder()
                                             .addScopeDecorator(MDCScopeDecorator.get())
                                             .build();
    private static final Tracing tracing = Tracing.newBuilder()
                                                  .currentTraceContext(traceCtx)
                                                  .build();

    private static final AtomicReference<Map<String, String>> serviceMdcContextRef = new AtomicReference<>();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {

            sb.service("/trace", (ctx, req) -> {
                ctx.log().whenComplete()
                   .thenAcceptAsync(log -> {
                       serviceMdcContextRef.set(MDC.getCopyOfContextMap());
                   }, ctx.eventLoop());
                return HttpResponse.of(
                        server.webClient(cb -> cb.decorator(BraveClient.newDecorator(tracing)))
                              .get("/bar").aggregate().thenApply(res -> {
                                  return HttpResponse.of("OK");
                              }));
            });

            sb.service("/bar", (ctx, req) -> {
                return HttpResponse.of("OK");
            });

            sb.decorator(LoggingService.newDecorator());
            sb.decorator(BraveService.newDecorator(tracing));
        }
    };

    @Test
    void mdcScopeDecorator() throws InterruptedException {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .decorator(BraveClient.newDecorator(tracing))
                                          .decorator(LoggingClient.newDecorator())
                                          .build();

        final AtomicReference<Map<String, String>> clientMdcContextRef = new AtomicReference<>();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.prepare().get("/trace").execute().aggregate();
            final ClientRequestContext cctx = captor.get();
            cctx.log().whenComplete()
                .thenAcceptAsync(log -> {
                    clientMdcContextRef.set(MDC.getCopyOfContextMap());
                }, cctx.eventLoop());
        }

        await().untilAtomic(serviceMdcContextRef, Matchers.notNullValue());
        await().untilAtomic(clientMdcContextRef, Matchers.notNullValue());

        final Map<String, String> serviceMdcContext = serviceMdcContextRef.get();
        final String serviceTraceId = serviceMdcContext.get(BaggageFields.TRACE_ID.name());
        final String serviceSpanId = serviceMdcContext.get(BaggageFields.SPAN_ID.name());
        assertThat(serviceTraceId).isNotNull();
        assertThat(serviceSpanId).isNotNull();

        final Map<String, String> clientMdcContext = clientMdcContextRef.get();
        final String clientTraceId = clientMdcContext.get(BaggageFields.TRACE_ID.name());
        final String clientSpanId = clientMdcContext.get(BaggageFields.SPAN_ID.name());
        assertThat(clientTraceId).isEqualTo(serviceTraceId);
        assertThat(clientSpanId).isEqualTo(serviceSpanId);
    }
}
