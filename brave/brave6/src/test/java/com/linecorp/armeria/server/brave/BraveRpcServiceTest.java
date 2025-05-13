/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.server.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.brave.TestSpanCollector;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.common.brave.SpanTags;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import brave.Tracing;
import brave.handler.MutableSpan;
import brave.rpc.RpcRequestParser;
import brave.rpc.RpcResponseParser;
import brave.rpc.RpcTags;
import brave.rpc.RpcTracing;
import testing.brave.TestService;
import testing.brave.TestService.Iface;

class BraveRpcServiceTest {

    private static final String SAMPLE_HEADER = "x-should-sample";
    private static final TestSpanCollector spanHandler = new TestSpanCollector();
    private static final Tracing tracing =
            Tracing.newBuilder()
                   .currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                   .addSpanHandler(spanHandler)
                   .build();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final RpcTracing defaultParserTracing =
                    RpcTracing.newBuilder(tracing)
                              .serverRequestParser((request, context, span) -> {
                                  RpcRequestParser.DEFAULT.parse(request, context, span);
                                  BraveServerParsers.rpcRequestParser().parse(request, context, span);
                              })
                              .serverResponseParser((response, context, span) -> {
                                  RpcResponseParser.DEFAULT.parse(response, context, span);
                                  BraveServerParsers.rpcResponseParser().parse(response, context, span);
                              })
                              .serverSampler(req -> {
                                  final ServiceRequestContext ctx = (ServiceRequestContext) req.unwrap();
                                  return ctx.request().headers().contains(SAMPLE_HEADER);
                              })
                              .build();

            sb.service("/default-parser",
                       THttpService.builder()
                                   .decorate(BraveRpcService.newDecorator(defaultParserTracing))
                                   .addService((Iface) name -> "world")
                                   .build());

            final RpcTracing braveParserTracing = RpcTracing.newBuilder(tracing).build();
            sb.service("/brave-parser",
                       THttpService.builder()
                                   .decorate(BraveRpcService.newDecorator(braveParserTracing))
                                   .addService((Iface) name -> "world")
                                   .build());
        }
    };

    @AfterEach
    void afterEach() {
        assertThat(spanHandler.spans()).isEmpty();
    }

    @AfterAll
    static void afterAll() throws Exception {
        tracing.close();
    }

    @Test
    void rpcSampling() throws Exception {
        final TestService.Iface samplingIface =
                ThriftClients.builder(server.httpUri().resolve("/default-parser"))
                             .addHeader(SAMPLE_HEADER, true)
                             .build(Iface.class);
        assertThat(samplingIface.hello("/")).isEqualTo("world");
        await().untilAsserted(() -> assertThat(spanHandler.spans()).hasSize(1));
        final MutableSpan span = spanHandler.spans().take();
        assertThat(span.tag(RpcTags.SERVICE.key())).isEqualTo("testing.brave.TestService$Iface");
        assertThat(span.tag(RpcTags.METHOD.key())).isEqualTo("hello");
    }

    @Test
    void rpcNoSampling() throws Exception {
        final TestService.Iface iface =
                ThriftClients.newClient(server.httpUri().resolve("/default-parser"), Iface.class);
        assertThat(iface.hello("/")).isEqualTo("world");
        await().pollDelay(1, TimeUnit.SECONDS).untilAsserted(() -> assertThat(spanHandler.spans()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/default-parser", "/brave-parser"})
    void parserBehavior(String path) throws Exception {
        final TestService.Iface iface =
                ThriftClients.builder(server.httpUri().resolve(path))
                             .addHeader(SAMPLE_HEADER, true)
                             .build(Iface.class);
        assertThat(iface.hello("/")).isEqualTo("world");

        await().untilAsserted(() -> assertThat(spanHandler.spans()).hasSize(1));
        final MutableSpan span = spanHandler.spans().take();
        final ServiceRequestContext sctx = server.requestContextCaptor().poll();
        final RequestLog slog = sctx.log().whenComplete().join();

        // wire annotations are recorded
        assertThat(span.startTimestamp()).isEqualTo(slog.requestStartTimeMicros());
        final Map<Long, String> annotations = ImmutableMap.copyOf(span.annotations());
        assertThat(annotations).containsValues("wr", "ws");

        // brave default tags are recorded
        assertThat(span.tag(RpcTags.SERVICE.key())).isEqualTo("testing.brave.TestService$Iface");
        assertThat(span.tag(RpcTags.METHOD.key())).isEqualTo("hello");

        if ("/brave-parser".equals(path)) {
            // armeria default tags are not recorded
            assertThat(span.tags()).doesNotContainKey(SpanTags.TAG_HTTP_SERIALIZATION_FORMAT);
        } else {
            // armeria default tags are recorded
            assertThat(span.tag(SpanTags.TAG_HTTP_SERIALIZATION_FORMAT)).isEqualTo("tbinary");
        }
    }
}
