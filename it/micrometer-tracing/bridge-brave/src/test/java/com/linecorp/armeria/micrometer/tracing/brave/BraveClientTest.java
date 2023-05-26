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

package com.linecorp.armeria.micrometer.tracing.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.brave.ArmeriaHttpClientParser;
import com.linecorp.armeria.client.brave.BraveClient;
import com.linecorp.armeria.client.endpoint.EmptyEndpointGroupException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.micrometer.tracing.client.TracingClient;
import com.linecorp.armeria.micrometer.tracing.common.ArmeriaCurrentTraceContext;

import brave.Span.Kind;
import brave.Tracing;
import brave.handler.MutableSpan;
import brave.http.HttpClientHandler;
import brave.http.HttpClientRequest;
import brave.http.HttpClientResponse;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.ScopeDecorator;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveHttpClientHandler;
import io.micrometer.tracing.brave.bridge.BraveTracer;

class BraveClientTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_SPAN = "hello";

    @AfterEach
    void tearDown() {
        Tracing.current().close();
    }

    static Stream<Arguments> newDecoratorArgs() {
        return Stream.of(
                Arguments.of(BraveCurrentTraceContext.fromBrave(ThreadLocalCurrentTraceContext.create())),
                Arguments.of(BraveCurrentTraceContext.fromBrave(RequestContextCurrentTraceContext.ofDefault())),
                Arguments.of(ArmeriaCurrentTraceContext.of())
        );
    }

    @ParameterizedTest
    @MethodSource("newDecoratorArgs")
    void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextNotConfigured(
            io.micrometer.tracing.CurrentTraceContext currentTraceContext) {
        final HttpTracing tracing = HttpTracing.create(Tracing.newBuilder().build());
        final HttpClientHandler<HttpClientRequest, HttpClientResponse> braveHttpHandler =
                HttpClientHandler.create(tracing);
        final BraveTracer braveTracer = new BraveTracer(tracing.tracing().tracer(), currentTraceContext);
        TracingClient.newDecorator(braveTracer, new BraveHttpClientHandler(braveHttpHandler));
    }

    @Test
    void shouldSubmitSpanWhenSampled() throws Exception {
        final SpanCollector collector = new SpanCollector();

        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .addSpanHandler(collector)
                                       .sampler(Sampler.create(1.0f))
                                       .build();
        testRemoteInvocation(tracing, null, ArmeriaCurrentTraceContext.of());

        // check span name
        final MutableSpan span = collector.spans().poll(10, TimeUnit.SECONDS);
        assertThat(span).isNotNull();
        assertThat(span.name()).isEqualTo(TEST_SPAN);

        // check kind
        assertThat(span.kind()).isSameAs(Kind.CLIENT);

        // only one span should be submitted
        assertThat(collector.spans().poll(1, TimeUnit.SECONDS)).isNull();

        // check # of annotations (we add wire annotations)
        assertThat(span.annotations()).hasSize(2);
        assertTags(span);

        assertThat(span.traceId().length()).isEqualTo(16);

        // check duration is correctly set
        assertThat(span.finishTimestamp() - span.startTimestamp())
                .isLessThan(TimeUnit.SECONDS.toMicros(1));

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);

        // check remote service name
        assertThat(span.remoteServiceName()).isEqualTo(null);
    }

    @Test
    void shouldSubmitSpanWithCustomRemoteName() throws Exception {
        final SpanCollector collector = new SpanCollector();

        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .addSpanHandler(collector)
                                       .sampler(Sampler.create(1.0f))
                                       .build();
        testRemoteInvocation(tracing, "fooService", ArmeriaCurrentTraceContext.of());

        // check span name
        final MutableSpan span = collector.spans().poll(10, TimeUnit.SECONDS);

        // check tags
        assertThat(span).isNotNull();
        assertThat(span.tags()).containsEntry("http.host", "foo.com")
                               .containsEntry("http.method", "POST")
                               .containsEntry("http.path", "/hello/armeria")
                               .containsEntry("http.url", "http://foo.com/hello/armeria")
                               .containsEntry("http.protocol", "h2c");

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);

        // check remote service name
        assertThat(span.remoteServiceName()).isEqualTo("fooService");
    }

    @Test
    void scopeDecorator() throws Exception {
        final SpanCollector collector = new SpanCollector();
        final AtomicInteger scopeDecoratorCallingCounter = new AtomicInteger();
        final ScopeDecorator scopeDecorator = (currentSpan, scope) -> {
            scopeDecoratorCallingCounter.getAndIncrement();
            return scope;
        };
        final CurrentTraceContext traceContext =
                RequestContextCurrentTraceContext.builder()
                                                 .addScopeDecorator(scopeDecorator)
                                                 .build();

        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .currentTraceContext(traceContext)
                                       .addSpanHandler(collector)
                                       .sampler(Sampler.create(1.0f))
                                       .build();
        testRemoteInvocation(tracing, null,
                             new BraveCurrentTraceContext(traceContext));

        // check span name
        final MutableSpan span = collector.spans().poll(10, TimeUnit.SECONDS);

        // check tags
        assertTags(span);

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);
        // check the client invocation had the current span in scope.
        assertThat(scopeDecoratorCallingCounter.get()).isOne();
    }

    @Test
    void shouldNotSubmitSpanWhenNotSampled() throws Exception {
        final SpanCollector collector = new SpanCollector();
        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .addSpanHandler(collector)
                                       .sampler(Sampler.create(0.0f))
                                       .build();
        testRemoteInvocation(tracing, null, ArmeriaCurrentTraceContext.of());

        assertThat(collector.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void testEmptyEndpointTags() {
        final SpanCollector collector = new SpanCollector();
        final Tracing tracing = Tracing.newBuilder()
                                       .addSpanHandler(collector)
                                       .currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                                       .sampler(Sampler.ALWAYS_SAMPLE)
                                       .build();
        final BlockingWebClient blockingWebClient =
                WebClient.builder(SessionProtocol.HTTP, EndpointGroup.of())
                         .decorator(BraveClient.newDecorator(tracing)).build()
                         .blocking();
        assertThatThrownBy(() -> blockingWebClient.get("/"))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCauseInstanceOf(EmptyEndpointGroupException.class);
        assertThat(collector.spans()).hasSize(1);
        final MutableSpan span = collector.spans().poll();
        assertThat(span.tag("http.host")).isEqualTo("UNKNOWN");
        assertThat(span.tag("http.url")).isEqualTo("http:/");
    }

    private static void testRemoteInvocation(
            Tracing tracing, @Nullable String remoteServiceName,
            io.micrometer.tracing.CurrentTraceContext currentTraceContext)
            throws Exception {

        HttpTracing httpTracing = HttpTracing.newBuilder(tracing)
                                             .clientRequestParser(ArmeriaHttpClientParser.get())
                                             .clientResponseParser(ArmeriaHttpClientParser.get())
                                             .build();
        if (remoteServiceName != null) {
            httpTracing = httpTracing.clientOf(remoteServiceName);
        }

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
        ctx.logBuilder().endRequest();

        try (SafeCloseable ignored = ctx.push()) {
            final HttpClient delegate = mock(HttpClient.class);
            when(delegate.execute(any(), any())).thenReturn(res);

            final TracingClient stub =
                    tracingClient(currentTraceContext, httpTracing).apply(delegate);
            // do invoke
            final HttpResponse actualRes = stub.execute(ctx, actualReq);

            assertThat(actualRes).isEqualTo(res);

            verify(delegate, times(1)).execute(same(ctx), argThat(arg -> {
                final RequestHeaders headers = arg.headers();
                return headers.contains(HttpHeaderNames.of("x-b3-traceid")) &&
                       headers.contains(HttpHeaderNames.of("x-b3-spanid")) &&
                       headers.contains(HttpHeaderNames.of("x-b3-sampled"));
            }));
        }

        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        ctx.logBuilder().responseFirstBytesTransferred();
        ctx.logBuilder().responseContent(rpcRes, res);
        ctx.logBuilder().endResponse();
    }

    private static Function<? super HttpClient, TracingClient> tracingClient(
            io.micrometer.tracing.CurrentTraceContext currentTraceContext,
            HttpTracing httpTracing) {
        final HttpClientHandler<HttpClientRequest, HttpClientResponse> braveHttpHandler =
                HttpClientHandler.create(httpTracing);
        final BraveTracer braveTracer = new BraveTracer(httpTracing.tracing().tracer(), currentTraceContext);
        return TracingClient.newDecorator(braveTracer, new BraveHttpClientHandler(braveHttpHandler));
    }

    private static void assertTags(@Nullable MutableSpan span) {
        assertThat(span).isNotNull();
        assertThat(span.tags()).containsEntry("http.host", "foo.com")
                               .containsEntry("http.method", "POST")
                               .containsEntry("http.path", "/hello/armeria")
                               .containsEntry("http.url", "http://foo.com/hello/armeria")
                               .containsEntry("http.protocol", "h2c");
    }
}
