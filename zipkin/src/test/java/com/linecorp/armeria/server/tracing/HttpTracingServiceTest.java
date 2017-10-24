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

import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.tracing.HelloService;
import com.linecorp.armeria.common.tracing.SpanCollectingReporter;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.Tracing;
import brave.sampler.Sampler;
import io.netty.channel.Channel;
import zipkin2.Span;
import zipkin2.Span.Kind;

public class HttpTracingServiceTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_METHOD = "hello";

    @Test(timeout = 20000)
    public void shouldSubmitSpanWhenRequestIsSampled() throws Exception {
        SpanCollectingReporter reporter = testServiceInvocation(1.0f);

        // check span name
        Span span = reporter.spans().take();
        assertThat(span.name()).isEqualTo(TEST_METHOD);

        // check kind
        assertThat(span.kind() == Kind.SERVER);

        // only one span should be submitted
        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();

        // check # of annotations (zipkin2 format does not use them by default)
        assertThat(span.annotations()).isEmpty();

        // check tags
        assertThat(span.tags()).containsAllEntriesOf(ImmutableMap.of(
                "http.host", "localhost",
                "http.method", "POST",
                "http.path", "/hello/trustin",
                "http.status_code", "200",
                "http.url", "none+h2c://localhost/hello/trustin"));

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);
    }

    @Test
    public void shouldNotSubmitSpanWhenRequestIsNotSampled() throws Exception {
        SpanCollectingReporter reporter = testServiceInvocation(0.0f);

        // don't submit any spans
        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    private static SpanCollectingReporter testServiceInvocation(float samplingRate) throws Exception {
        SpanCollectingReporter reporter = new SpanCollectingReporter();

        Tracing tracing = Tracing.newBuilder()
                                 .localServiceName(TEST_SERVICE)
                                 .spanReporter(reporter)
                                 .sampler(Sampler.create(samplingRate))
                                 .build();

        @SuppressWarnings("unchecked")
        Service<HttpRequest, HttpResponse> delegate = mock(Service.class);

        final HttpTracingService stub = new HttpTracingService(delegate, tracing);

        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/hello/trustin");
        final RpcRequest rpcReq = RpcRequest.of(HelloService.Iface.class, "hello", "trustin");
        final HttpResponse res = HttpResponse.of(HttpStatus.OK);
        final RpcResponse rpcRes = RpcResponse.of("Hello, trustin!");
        final DefaultRequestLog log = new DefaultRequestLog(ctx);
        log.startRequest(mock(Channel.class), H2C, "localhost");
        log.requestContent(rpcReq, req);
        log.endRequest();

        // HttpTracingService prefers RpcRequest.method() to ctx.method(), so "POST" should be ignored.
        when(ctx.method()).thenReturn(HttpMethod.POST);
        when(ctx.log()).thenReturn(log);
        when(ctx.logBuilder()).thenReturn(log);
        when(ctx.path()).thenReturn("/hello/trustin");
        ctx.onEnter(isA(Consumer.class));
        ctx.onExit(isA(Consumer.class));
        when(delegate.serve(ctx, req)).thenReturn(res);

        // do invoke
        stub.serve(ctx, req);

        verify(delegate, times(1)).serve(eq(ctx), eq(req));
        log.responseHeaders(HttpHeaders.of(HttpStatus.OK));
        log.responseContent(rpcRes, res);
        log.endResponse();

        return reporter;
    }
}
