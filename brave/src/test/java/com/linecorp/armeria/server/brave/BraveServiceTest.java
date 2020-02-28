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

package com.linecorp.armeria.server.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.brave.HelloService;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.brave.SpanCollectingReporter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.Tracing;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.ScopeDecorator;
import brave.sampler.Sampler;
import zipkin2.Span;
import zipkin2.Span.Kind;
import zipkin2.reporter.Reporter;

class BraveServiceTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_METHOD = "hello";

    @AfterEach
    public void tearDown() {
        Tracing.current().close();
    }

    @Test
    void newDecorator_shouldFailFastWhenRequestContextCurrentTraceContextNotConfigured() {
        assertThatThrownBy(() -> BraveService.newDecorator(HttpTracing.create(Tracing.newBuilder().build())))
                .isInstanceOf(IllegalStateException.class).hasMessage(
                "Tracing.currentTraceContext is not a RequestContextCurrentTraceContext scope. Please " +
                "call Tracing.Builder.currentTraceContext(RequestContextCurrentTraceContext.ofDefault())."
        );
    }

    @Test
    void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextConfigured() {
        BraveService.newDecorator(
                HttpTracing.create(
                        Tracing.newBuilder()
                               .currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                               .build()));
    }

    @Test
    void shouldSubmitSpanWhenRequestIsSampled() throws Exception {
        final SpanCollectingReporter reporter = new SpanCollectingReporter();
        final RequestLog requestLog = testServiceInvocation(reporter,
                                                            RequestContextCurrentTraceContext.ofDefault(),
                                                            1.0f);

        // check span name
        final Span span = reporter.spans().take();
        assertThat(span.name()).isEqualTo(TEST_METHOD);

        // check kind
        assertThat(span.kind()).isSameAs(Kind.SERVER);

        // only one span should be submitted
        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();

        // check # of annotations (we add wire annotations)
        assertThat(span.annotations()).hasSize(2);

        // check tags
        assertTags(span);

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);

        // check duration is correct from request log
        assertThat(span.durationAsLong())
                .isEqualTo(requestLog.totalDurationNanos() / 1000);
    }

    @Test
    void shouldNotSubmitSpanWhenRequestIsNotSampled() throws Exception {
        final SpanCollectingReporter reporter = new SpanCollectingReporter();
        testServiceInvocation(reporter, RequestContextCurrentTraceContext.ofDefault(), 0.0f);

        // don't submit any spans
        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void scopeDecorator() throws Exception {
        final AtomicInteger scopeDecoratorCallingCounter = new AtomicInteger();
        final ScopeDecorator scopeDecorator = (currentSpan, scope) -> {
            scopeDecoratorCallingCounter.getAndIncrement();
            return scope;
        };
        final CurrentTraceContext traceContext =
                RequestContextCurrentTraceContext.builder()
                                                 .addScopeDecorator(scopeDecorator)
                                                 .build();
        final SpanCollectingReporter reporter = new SpanCollectingReporter();
        testServiceInvocation(reporter, traceContext, 1.0f);

        // check span name
        final Span span = reporter.spans().take();

        // check tags
        assertTags(span);

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);
        // check the service invocation had the current span in scope.
        assertThat(scopeDecoratorCallingCounter.get()).isOne();
    }

    private static RequestLog testServiceInvocation(Reporter<Span> reporter,
                                                    CurrentTraceContext traceContext,
                                                    float samplingRate) throws Exception {
        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .spanReporter(reporter)
                                       .currentTraceContext(traceContext)
                                       .sampler(Sampler.create(samplingRate))
                                       .build();

        final HttpTracing httpTracing = HttpTracing.newBuilder(tracing)
                                                   .serverRequestParser(ArmeriaHttpServerParser.get())
                                                   .serverResponseParser(ArmeriaHttpServerParser.get())
                                                   .build();

        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "foo.com"));
        final ServiceRequestContext ctx = ServiceRequestContext.builder(req).build();
        final RpcRequest rpcReq = RpcRequest.of(HelloService.Iface.class, "hello", "trustin");
        final HttpResponse res = HttpResponse.of(HttpStatus.OK);
        final RpcResponse rpcRes = RpcResponse.of("Hello, trustin!");
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.requestContent(rpcReq, req);
        logBuilder.endRequest();

        try (SafeCloseable ignored = ctx.push()) {
            final HttpService delegate = mock(HttpService.class);
            final BraveService service = BraveService.newDecorator(httpTracing).apply(delegate);
            when(delegate.serve(ctx, req)).thenReturn(res);

            // do invoke
            service.serve(ctx, req);

            verify(delegate, times(1)).serve(eq(ctx), eq(req));
        }

        logBuilder.responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        logBuilder.responseFirstBytesTransferred();
        logBuilder.responseContent(rpcRes, res);
        logBuilder.endResponse();
        return ctx.log().ensureComplete();
    }

    private static void assertTags(Span span) {
        assertThat(span.tags()).containsEntry("http.host", "foo.com")
                               .containsEntry("http.method", "POST")
                               .containsEntry("http.path", "/hello/trustin")
                               .containsEntry("http.url", "http://foo.com/hello/trustin")
                               .containsEntry("http.protocol", "h2c");
    }
}
