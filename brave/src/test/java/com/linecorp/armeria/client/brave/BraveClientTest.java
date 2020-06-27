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

package com.linecorp.armeria.client.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import com.linecorp.armeria.common.brave.HelloService;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.brave.SpanCollector;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.SafeCloseable;

import brave.Span.Kind;
import brave.Tracing;
import brave.handler.MutableSpan;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.ScopeDecorator;
import brave.sampler.Sampler;

class BraveClientTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_SPAN = "hello";

    @AfterEach
    void tearDown() {
        Tracing.current().close();
    }

    @Test
    void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextNotConfigured() {
        BraveClient.newDecorator(HttpTracing.create(Tracing.newBuilder().build()));
    }

    @Test
    void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextConfigured() {
        BraveClient.newDecorator(
                HttpTracing.create(
                        Tracing.newBuilder().currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                               .build()));
    }

    @Test
    void shouldSubmitSpanWhenSampled() throws Exception {
        final SpanCollector collector = new SpanCollector();

        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .addSpanHandler(collector)
                                       .sampler(Sampler.create(1.0f))
                                       .build();
        final RequestLog requestLog = testRemoteInvocation(tracing, null);

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

        // check duration is correct from request log
        assertThat(span.finishTimestamp() - span.startTimestamp())
                .isEqualTo(requestLog.totalDurationNanos() / 1000);

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
        testRemoteInvocation(tracing, "fooService");

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
        testRemoteInvocation(tracing, null);

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
        testRemoteInvocation(tracing, null);

        assertThat(collector.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    private static RequestLog testRemoteInvocation(Tracing tracing, @Nullable String remoteServiceName)
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
        final HttpRequest actualReq = ctx.request();
        assertThat(actualReq).isNotNull();

        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().requestContent(rpcReq, actualReq);
        ctx.logBuilder().endRequest();

        try (SafeCloseable ignored = ctx.push()) {
            final HttpClient delegate = mock(HttpClient.class);
            when(delegate.execute(any(), any())).thenReturn(res);

            final BraveClient stub = BraveClient.newDecorator(httpTracing).apply(delegate);
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
        return ctx.log().ensureComplete();
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
