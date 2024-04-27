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

package com.linecorp.armeria.server.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.observation.MicrometerObservationRegistryUtils;
import com.linecorp.armeria.internal.common.observation.SpanCollector;
import com.linecorp.armeria.internal.testing.ImmediateEventLoop;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientHttpService;
import com.linecorp.armeria.server.TransientServiceOption;

import brave.Span.Kind;
import brave.Tracing;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.ScopeDecorator;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;

class ObservationServiceTest {

    private static final String TEST_SERVICE = "test-service";

    @AfterEach
    public void tearDown() {
        Tracing.current().close();
    }

    @Test
    void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextConfigured() {
        ObservationService.newDecorator(
                MicrometerObservationRegistryUtils.observationRegistry(HttpTracing.create(
                        Tracing.newBuilder()
                               .currentTraceContext(ThreadLocalCurrentTraceContext.create())
                               .build())));
    }

    @Test
    void shouldSubmitSpanWhenRequestIsSampled() throws Exception {
        final SpanCollector collector = new SpanCollector();
        final RequestLog requestLog = testServiceInvocation(collector,
                                                            ThreadLocalCurrentTraceContext.create(),
                                                            1.0f);

        // check span name
        final MutableSpan span = collector.spans().take();
        assertThat(span.name()).isEqualTo(requestLog.fullName());

        // check kind
        assertThat(span.kind()).isSameAs(Kind.SERVER);

        // only one span should be submitted
        assertThat(collector.spans().poll(1, TimeUnit.SECONDS)).isNull();

        // check # of annotations (we add wire annotations)
        assertThat(span.annotations()).hasSize(2);

        // check tags
        assertTags(span);

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);

        // check duration is correct from request log - we do it differently
        assertThat(span.finishTimestamp() - span.startTimestamp())
                .isNotEqualTo(requestLog.totalDurationNanos() / 1000);
    }

    @Test
    void shouldNotSubmitSpanWhenRequestIsNotSampled() throws Exception {
        final SpanCollector collector = new SpanCollector();
        testServiceInvocation(collector, ThreadLocalCurrentTraceContext.create(), 0.0f);

        // don't submit any spans
        assertThat(collector.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void scopeDecorator() throws Exception {
        final AtomicInteger scopeDecoratorCallingCounter = new AtomicInteger();
        final ScopeDecorator scopeDecorator = (currentSpan, scope) -> {
            scopeDecoratorCallingCounter.getAndIncrement();
            return scope;
        };
        final CurrentTraceContext traceContext =
                ThreadLocalCurrentTraceContext.newBuilder()
                                              .addScopeDecorator(scopeDecorator)
                                              .build();
        final SpanCollector collector = new SpanCollector();
        testServiceInvocation(collector, traceContext, 1.0f);

        // check span name
        final MutableSpan span = collector.spans().take();

        // check tags
        assertTags(span);

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);
        // check the service invocation had the current span in scope.
        assertThat(scopeDecoratorCallingCounter.get()).isOne();
    }

    @Test
    void transientService() throws Exception {
        final SpanCollector collector = new SpanCollector();
        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .addSpanHandler(collector)
                                       .currentTraceContext(ThreadLocalCurrentTraceContext.create())
                                       .sampler(Sampler.ALWAYS_SAMPLE)
                                       .build();

        final HttpService transientService = new TransientHttpService() {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                return HttpResponse.of(HttpStatus.OK);
            }

            @Override
            public Set<TransientServiceOption> transientServiceOptions() {
                return Collections.emptySet();
            }
        };

        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/internal/healthcheck",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "foo.com"));
        final ServiceRequestContext ctx = ServiceRequestContext.builder(req)
                                                               .service(transientService)
                                                               .build();
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.endRequest();

        try (SafeCloseable ignored = ctx.push()) {
            final ObservationService
                    service = ObservationService.newDecorator(
                    MicrometerObservationRegistryUtils.observationRegistry(tracing)).apply(transientService);

            // do invoke
            final AggregatedHttpResponse res = service.serve(ctx, req).aggregate().join();
            logBuilder.responseHeaders(res.headers());
            logBuilder.responseFirstBytesTransferred();
            logBuilder.endResponse();
        }

        // don't submit any spans
        assertThat(collector.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    private static RequestLog testServiceInvocation(SpanHandler spanHandler,
                                                    CurrentTraceContext traceContext,
                                                    float samplingRate) throws Exception {
        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .addSpanHandler(spanHandler)
                                       .currentTraceContext(traceContext)
                                       .sampler(Sampler.create(samplingRate))
                                       .build();

        final HttpTracing httpTracing = HttpTracing.newBuilder(tracing)
                                                   .build();

        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "foo.com"));
        final ServiceRequestContext ctx = ServiceRequestContext.builder(req)
                                                               .eventLoop(ImmediateEventLoop.INSTANCE)
                                                               .build();
        final RpcRequest rpcReq = RpcRequest.of(ObservationServiceTest.class, "hello", "trustin");
        final HttpResponse res = HttpResponse.of(HttpStatus.OK);
        final RpcResponse rpcRes = RpcResponse.of("Hello, trustin!");
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.requestContent(rpcReq, req);
        logBuilder.endRequest();

        try (SafeCloseable ignored = ctx.push()) {
            final HttpService delegate = mock(HttpService.class);
            final ObservationService
                    service = ObservationService.newDecorator(
                    MicrometerObservationRegistryUtils.observationRegistry(httpTracing)).apply(delegate);
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

    private static void assertTags(MutableSpan span) {
        assertThat(span.tags()).containsEntry("http.host", "foo.com")
                               .containsEntry("http.method", "POST")
                               .containsEntry("http.path", "/hello/trustin")
                               .containsEntry("http.url", "http://foo.com/hello/trustin")
                               .containsEntry("http.protocol", "h2c");
    }
}
