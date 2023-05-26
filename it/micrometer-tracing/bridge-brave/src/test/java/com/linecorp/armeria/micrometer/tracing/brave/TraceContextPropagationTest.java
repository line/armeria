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

package com.linecorp.armeria.micrometer.tracing.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.brave.ArmeriaHttpClientParser;
import com.linecorp.armeria.client.brave.TraceContextPropagation;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.micrometer.tracing.client.TracingClient;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.brave.BraveService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import brave.Tracing;
import brave.http.HttpClientHandler;
import brave.http.HttpClientRequest;
import brave.http.HttpClientResponse;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.ThreadLocalCurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveHttpClientHandler;
import io.micrometer.tracing.brave.bridge.BraveTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;

class TraceContextPropagationTest {

    private static final CurrentTraceContext currentTraceContext =
            RequestContextCurrentTraceContext.builder().build();

    private static final Tracing tracing = Tracing.newBuilder()
                                                  .currentTraceContext(currentTraceContext)
                                                  .build();
    private static final BraveCurrentTraceContext context =
            new BraveCurrentTraceContext(tracing.currentTraceContext());
    private static final BraveTracer tracer = new BraveTracer(tracing.tracer(), context);

    private static final Map<String, TraceContext> traceContexts = new ConcurrentHashMap<>();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/foo/manual", (ctx, req) -> {
                final TraceContext traceContext = context.context();
                traceContexts.put("foo-service", traceContext);
                final CurrentTraceContext threadLocalContext = ThreadLocalCurrentTraceContext.create();

                final WebClient client = server.webClient(cb -> {
                    cb.contextCustomizer(TraceContextPropagation.inject(threadLocalContext::get))
                      .decorator((delegate, cctx, creq) -> {
                          cctx.log().whenComplete().thenAcceptAsync(log -> {
                              traceContexts.put("bar-client", context.context());
                          }, cctx.eventLoop());
                          return delegate.execute(cctx, creq);
                      })
                      .decorator(newDecorator())
                      .responseTimeoutMillis(0);
                });

                return HttpResponse.from(CompletableFuture.supplyAsync(() -> {
                    // Make sure the current thread is not context-aware.
                    assertThat(ServiceRequestContext.currentOrNull()).isNull();
                    assertThat(context.context()).isNull();
                    try (Scope ignored = threadLocalContext.newScope(
                            BraveTraceContext.toBrave(traceContext))) {
                        return client.get("/bar");
                    }
                }));
            });

            sb.service("/foo/auto", (ctx, req) -> {
                final TraceContext traceContext = context.context();
                traceContexts.put("foo-service", traceContext);

                final WebClient client = server.webClient(cb -> {
                    cb.decorator((delegate, cctx, creq) -> {
                          cctx.log().whenComplete().thenAcceptAsync(log -> {
                              traceContexts.put("bar-client", context.context());
                          }, cctx.eventLoop());
                          return delegate.execute(cctx, creq);
                      })
                      .decorator(newDecorator());
                });

                return client.get("/bar");
            });

            sb.annotatedService(new Object() {
                @Blocking
                @Get("/foo/blocking")
                public HttpResponse foo() {
                    final TraceContext traceContext = context.context();
                    traceContexts.put("foo-service", traceContext);
                    final WebClient client = server.webClient(cb -> {
                        cb.decorator((delegate, cctx, creq) -> {
                              cctx.log().whenComplete().thenAcceptAsync(log -> {
                                  traceContexts.put("bar-client", context.context());
                              }, cctx.eventLoop());
                              return delegate.execute(cctx, creq);
                          })
                          .decorator(newDecorator());
                    });

                    return client.get("/bar");
                }
            });

            sb.service("/bar", (ctx, req) -> {
                traceContexts.put("bar-service", context.context());
                return HttpResponse.of("bar");
            });
            sb.decorator(BraveService.newDecorator(tracing));
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @CsvSource({ "manual", "auto", "blocking" })
    @ParameterizedTest
    void propagation(String type) {
        final BlockingWebClient client = server.webClient(cb -> {
            cb.decorator((delegate, ctx, req) -> {
                traceContexts.put("foo-client", context.context());
                return delegate.execute(ctx, req);
            });
            cb.decorator(newDecorator());
        }).blocking();

        final AggregatedHttpResponse response = client.get("/foo/" + type);
        assertThat(response.contentUtf8()).isEqualTo("bar");

        await().untilAsserted(() -> assertThat(traceContexts).hasSize(4));
        final TraceContext fooClientTraceContext = traceContexts.get("foo-client");
        final TraceContext fooServiceTraceContext = traceContexts.get("foo-service");
        final TraceContext barClientTraceContext = traceContexts.get("bar-client");
        final TraceContext barServiceTraceContext = traceContexts.get("bar-service");

        // Check correlation
        final String traceId = fooClientTraceContext.traceId();
        assertThat(fooServiceTraceContext.traceId()).isEqualTo(traceId);
        assertThat(fooServiceTraceContext.spanId()).isEqualTo(traceId);
        assertThat(barClientTraceContext.traceId()).isEqualTo(traceId);
        assertThat(barClientTraceContext.parentId()).isEqualTo(fooServiceTraceContext.spanId());
        assertThat(barServiceTraceContext.traceId()).isEqualTo(traceId);
        assertThat(barServiceTraceContext.parentId()).isEqualTo(fooServiceTraceContext.spanId());
        assertThat(barServiceTraceContext.spanId()).isEqualTo(barClientTraceContext.spanId());
    }

    private static Function<? super HttpClient, TracingClient> newDecorator() {
        final HttpTracing httpTracing =
                HttpTracing.newBuilder(tracing)
                           .clientRequestParser(ArmeriaHttpClientParser.get())
                           .clientResponseParser(ArmeriaHttpClientParser.get())
                           .build();
        final HttpClientHandler<HttpClientRequest, HttpClientResponse> braveHttpHandler =
                HttpClientHandler.create(httpTracing);
        final BraveHttpClientHandler handler = new BraveHttpClientHandler(braveHttpHandler);
        return TracingClient.newDecorator(tracer, handler);
    }
}
