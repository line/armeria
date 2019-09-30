/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

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
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.tracing.HelloService;
import com.linecorp.armeria.common.tracing.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.tracing.SpanCollectingReporter;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;

import brave.Tracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.ScopeDecorator;
import brave.sampler.Sampler;
import zipkin2.Span;
import zipkin2.Span.Kind;

class TracingServiceTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_METHOD = "hello";

    @AfterEach
    public void tearDown() {
        Tracing.current().close();
    }

    @Test
    void newDecorator_shouldFailFastWhenRequestContextCurrentTraceContextNotConfigured() {
        assertThatThrownBy(() -> HttpTracingService.newDecorator(Tracing.newBuilder().build()))
                .isInstanceOf(IllegalStateException.class).hasMessage(
                "Tracing.currentTraceContext is not a RequestContextCurrentTraceContext scope. " +
                "Please call Tracing.Builder.currentTraceContext(RequestContextCurrentTraceContext.DEFAULT)."
        );
    }

    @Test
    void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextConfigured() {
        HttpTracingService.newDecorator(
                Tracing.newBuilder().currentTraceContext(RequestContextCurrentTraceContext.DEFAULT).build());
    }

    @Test
    void shouldSubmitSpanWhenRequestIsSampled() throws Exception {
        final SpanCollectingReporter reporter = testServiceInvocation(
                RequestContextCurrentTraceContext.DEFAULT, 1.0f);

        // check span name
        final Span span = reporter.spans().poll(10, TimeUnit.SECONDS);
        assertThat(span).isNotNull();
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
    }

    @Test
    void shouldNotSubmitSpanWhenRequestIsNotSampled() throws Exception {
        final SpanCollectingReporter reporter = testServiceInvocation(
                RequestContextCurrentTraceContext.DEFAULT, 0.0f);

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

        final SpanCollectingReporter reporter = testServiceInvocation(traceContext, 1.0f);

        // check span name
        final Span span = reporter.spans().poll(10, TimeUnit.SECONDS);

        // check tags
        assertTags(span);

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);
        assertThat(scopeDecoratorCallingCounter.get()).isEqualTo(1);
    }

    private static SpanCollectingReporter testServiceInvocation(CurrentTraceContext traceContext,
                                                                float samplingRate) throws Exception {
        final SpanCollectingReporter reporter = new SpanCollectingReporter();

        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .spanReporter(reporter)
                                       .currentTraceContext(traceContext)
                                       .sampler(Sampler.create(samplingRate))
                                       .build();

        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "foo.com"));
        final ServiceRequestContext ctx = ServiceRequestContextBuilder.of(req)
                                                                      .build();
        final RpcRequest rpcReq = RpcRequest.of(HelloService.Iface.class, "hello", "trustin");
        final HttpResponse res = HttpResponse.of(HttpStatus.OK);
        final RpcResponse rpcRes = RpcResponse.of("Hello, trustin!");
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.requestContent(rpcReq, req);
        logBuilder.endRequest();

        try (SafeCloseable ignored = ctx.push()) {
            @SuppressWarnings("unchecked")
            final Service<HttpRequest, HttpResponse> delegate = mock(Service.class);
            final HttpTracingService service = HttpTracingService.newDecorator(tracing).apply(delegate);
            when(delegate.serve(ctx, req)).thenReturn(res);

            // do invoke
            service.serve(ctx, req);

            verify(delegate, times(1)).serve(eq(ctx), eq(req));
        }

        logBuilder.responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        logBuilder.responseFirstBytesTransferred();
        logBuilder.responseContent(rpcRes, res);
        logBuilder.endResponse();
        return reporter;
    }

    private static void assertTags(@Nullable Span span) {
        assertThat(span).isNotNull();
        assertThat(span.tags()).containsEntry("http.host", "foo.com")
                               .containsEntry("http.method", "POST")
                               .containsEntry("http.path", "/hello/trustin")
                               .containsEntry("http.status_code", "200")
                               .containsEntry("http.url", "http://foo.com/hello/trustin")
                               .containsEntry("http.protocol", "h2c");
    }
}
