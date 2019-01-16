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

import org.junit.After;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.tracing.HelloService;
import com.linecorp.armeria.common.tracing.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.tracing.SpanCollectingReporter;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;

import brave.Tracing;
import brave.sampler.Sampler;
import zipkin2.Span;
import zipkin2.Span.Kind;

public class HttpTracingServiceTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_METHOD = "hello";

    @After
    public void tearDown() {
        Tracing.current().close();
    }

    @Test
    public void newDecorator_shouldFailFastWhenRequestContextCurrentTraceContextNotConfigured() {
        assertThatThrownBy(() -> HttpTracingService.newDecorator(Tracing.newBuilder().build()))
                .isInstanceOf(IllegalStateException.class).hasMessage(
                "Tracing.currentTraceContext is not a RequestContextCurrentTraceContext scope. " +
                "Please call Tracing.Builder.currentTraceContext(RequestContextCurrentTraceContext.INSTANCE)."
        );
    }

    @Test
    public void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextConfigured() {
        HttpTracingService.newDecorator(
                Tracing.newBuilder().currentTraceContext(RequestContextCurrentTraceContext.DEFAULT).build());
    }

    @Test(timeout = 20000)
    public void shouldSubmitSpanWhenRequestIsSampled() throws Exception {
        final SpanCollectingReporter reporter = testServiceInvocation(1.0f);

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
        assertThat(span.tags()).containsAllEntriesOf(ImmutableMap.of(
                "http.host", "foo.com",
                "http.method", "POST",
                "http.path", "/hello/trustin",
                "http.status_code", "200",
                "http.url", "none+h2c://foo.com/hello/trustin"));

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);
    }

    @Test
    public void shouldNotSubmitSpanWhenRequestIsNotSampled() throws Exception {
        final SpanCollectingReporter reporter = testServiceInvocation(0.0f);

        // don't submit any spans
        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    private static SpanCollectingReporter testServiceInvocation(float samplingRate) throws Exception {
        final SpanCollectingReporter reporter = new SpanCollectingReporter();

        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .spanReporter(reporter)
                                       .sampler(Sampler.create(samplingRate))
                                       .build();

        @SuppressWarnings("unchecked")
        final Service<HttpRequest, HttpResponse> delegate = mock(Service.class);

        final HttpTracingService stub = new HttpTracingService(delegate, tracing);

        final HttpRequest req = HttpRequest.of(HttpHeaders.of(HttpMethod.POST, "/hello/trustin")
                                                          .authority("foo.com"));
        final ServiceRequestContext ctx = ServiceRequestContextBuilder.of(req)
                                                                      .service(stub)
                                                                      .build();

        final RpcRequest rpcReq = RpcRequest.of(HelloService.Iface.class, "hello", "trustin");
        final HttpResponse res = HttpResponse.of(HttpStatus.OK);
        final RpcResponse rpcRes = RpcResponse.of("Hello, trustin!");
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.requestContent(rpcReq, req);
        logBuilder.endRequest();

        when(delegate.serve(ctx, req)).thenReturn(res);

        // do invoke
        stub.serve(ctx, req);

        verify(delegate, times(1)).serve(eq(ctx), eq(req));
        logBuilder.responseHeaders(HttpHeaders.of(HttpStatus.OK));
        logBuilder.responseFirstBytesTransferred();
        logBuilder.responseContent(rpcRes, res);
        logBuilder.endResponse();

        return reporter;
    }
}
