/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.micrometer.tracing.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.micrometer.tracing.brave.HelloService;
import com.linecorp.armeria.micrometer.tracing.client.TracingClient;
import com.linecorp.armeria.micrometer.tracing.client.TracingHttpClientParser;

import io.micrometer.tracing.SamplerFunction;
import io.micrometer.tracing.otel.bridge.DefaultHttpClientAttributesGetter;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelHttpClientHandler;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;

class OtelClientTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();

    private final Tracer tracer = otelTesting.getOpenTelemetry().getTracer("test");

    @Test
    void testBasicCase() throws Exception {
        testRemoteInvocation();

        otelTesting.assertTraces().hasSize(1);
        final List<SpanData> spans = otelTesting.getSpans();
        assertThat(spans).hasSize(1);
        final SpanData span = spans.get(0);

        assertThat(span).isNotNull();
        assertThat(span.getName()).isEqualTo("hello");

        // check kind
        assertThat(span.getKind()).isSameAs(SpanKind.CLIENT);

        // check # of events and annotations
        assertThat(span.getEvents()).hasSize(2);
        assertThat(span.getEvents().stream().map(EventData::getName))
                .containsExactlyInAnyOrder("ws", "wr");
        assertTags(span);

        assertThat(span.getTraceId().length()).isEqualTo(32);

        // check duration is correctly set
        assertThat(span.getEndEpochNanos() - span.getStartEpochNanos())
                .isLessThan(TimeUnit.SECONDS.toNanos(1));
    }

    private void testRemoteInvocation() throws Exception {

        final OtelHttpClientHandler httpClientHandler = new OtelHttpClientHandler(
                otelTesting.getOpenTelemetry(),
                TracingHttpClientParser.of(),
                TracingHttpClientParser.of(),
                SamplerFunction.alwaysSample(),
                new DefaultHttpClientAttributesGetter());

        // prepare parameters
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/armeria",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "foo.com"));
        final RpcRequest rpcReq = RpcRequest.of(HelloService.Iface.class, "hello", "Armeria");
        final HttpResponse res = HttpResponse.of(HttpStatus.OK);
        final RpcResponse rpcRes = RpcResponse.of("Hello, Armeria!");
        final ClientRequestContext ctx = ClientRequestContext.builder(req).build();
        // the authority is extracted even when the request doesn't declare an authority
        final RequestHeaders headersWithoutAuthority =
                req.headers().toBuilder().removeAndThen(HttpHeaderNames.AUTHORITY).build();
        ctx.updateRequest(req.withHeaders(headersWithoutAuthority));
        final HttpRequest actualReq = ctx.request();
        assertThat(actualReq).isNotNull();

        ctx.logBuilder().startRequest();
        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().requestContent(rpcReq, actualReq);
        ctx.logBuilder().requestContent(null, actualReq);
        ctx.logBuilder().endRequest();

        try (SafeCloseable ignored = ctx.push()) {
            final HttpClient delegate = mock(HttpClient.class);
            when(delegate.execute(any(), any())).thenReturn(res);

            final OtelTracer otelTracer = new OtelTracer(tracer, new OtelCurrentTraceContext(),
                                                         event -> {});
            final TracingClient stub =
                    TracingClient.newDecorator(otelTracer, httpClientHandler).apply(delegate);
            // do invoke
            final HttpResponse actualRes = stub.execute(ctx, actualReq);

            assertThat(actualRes).isEqualTo(res);

            verify(delegate, times(1)).execute(same(ctx), argThat(arg -> {
                final RequestHeaders headers = arg.headers();
                return headers.contains(HttpHeaderNames.of("traceparent"));
            }));
        }

        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        ctx.logBuilder().responseFirstBytesTransferred();
        ctx.logBuilder().responseContent(rpcRes, res);
        ctx.logBuilder().endResponse();
    }

    private static void assertTags(@Nullable SpanData span) {
        assertThat(span).isNotNull();
        assertThat(span.getAttributes().asMap())
                .containsEntry(AttributeKey.stringKey("http.host"), "foo.com")
                .containsEntry(AttributeKey.stringKey("http.method"), "POST")
                .containsEntry(AttributeKey.stringKey("http.path"), "/hello/armeria")
                .containsEntry(AttributeKey.stringKey("http.url"), "http://foo.com/hello/armeria")
                .containsEntry(AttributeKey.stringKey("http.protocol"), "h2c");
    }
}
