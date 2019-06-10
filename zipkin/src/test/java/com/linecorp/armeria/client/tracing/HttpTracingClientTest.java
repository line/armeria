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

package com.linecorp.armeria.client.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.Test;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.tracing.HelloService;
import com.linecorp.armeria.common.tracing.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.tracing.SpanCollectingReporter;

import brave.Tracing;
import brave.sampler.Sampler;
import zipkin2.Span;
import zipkin2.Span.Kind;

public class HttpTracingClientTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_SPAN = "hello";

    @After
    public void tearDown() {
        Tracing.current().close();
    }

    @Test
    public void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextNotConfigured() {
        HttpTracingClient.newDecorator(Tracing.newBuilder().build());
    }

    @Test
    public void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextConfigured() {
        HttpTracingClient.newDecorator(
                Tracing.newBuilder().currentTraceContext(RequestContextCurrentTraceContext.DEFAULT).build());
    }

    @Test(timeout = 20000)
    public void shouldSubmitSpanWhenSampled() throws Exception {
        final SpanCollectingReporter reporter = new SpanCollectingReporter();

        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .spanReporter(reporter)
                                       .sampler(Sampler.create(1.0f))
                                       .build();
        testRemoteInvocation(tracing, null);

        // check span name
        final Span span = reporter.spans().take();
        assertThat(span.name()).isEqualTo(TEST_SPAN);

        // check kind
        assertThat(span.kind()).isSameAs(Kind.CLIENT);

        // only one span should be submitted
        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();

        // check # of annotations (we add wire annotations)
        assertThat(span.annotations()).hasSize(2);

        // check tags
        assertThat(span.tags()).containsEntry("http.host", "foo.com")
                               .containsEntry("http.method", "POST")
                               .containsEntry("http.path", "/hello/armeria")
                               .containsEntry("http.status_code", "200")
                               .containsEntry("http.url", "http://foo.com/hello/armeria")
                               .containsEntry("http.protocol", "h2c");

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);

        // check remote service name
        assertThat(span.remoteServiceName()).isEqualTo(null);
    }

    @Test(timeout = 20000)
    public void shouldSubmitSpanWithCustomRemoteName() throws Exception {
        final SpanCollectingReporter reporter = new SpanCollectingReporter();

        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .spanReporter(reporter)
                                       .sampler(Sampler.create(1.0f))
                                       .build();
        testRemoteInvocation(tracing, "fooService");

        // check span name
        final Span span = reporter.spans().take();

        // check tags
        assertThat(span.tags()).containsEntry("http.host", "foo.com")
                               .containsEntry("http.method", "POST")
                               .containsEntry("http.path", "/hello/armeria")
                               .containsEntry("http.status_code", "200")
                               .containsEntry("http.url", "http://foo.com/hello/armeria")
                               .containsEntry("http.protocol", "h2c");

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);

        // check remote service name, lower-cased
        assertThat(span.remoteServiceName()).isEqualTo("fooservice");
    }

    @Test
    public void shouldNotSubmitSpanWhenNotSampled() throws Exception {
        final SpanCollectingReporter reporter = new SpanCollectingReporter();
        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .spanReporter(reporter)
                                       .sampler(Sampler.create(0.0f))
                                       .build();
        testRemoteInvocation(tracing, null);

        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    private static void testRemoteInvocation(Tracing tracing, @Nullable String remoteServiceName)
            throws Exception {

        // prepare parameters
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/armeria",
                                                                 HttpHeaderNames.AUTHORITY, "foo.com"));
        final RpcRequest rpcReq = RpcRequest.of(HelloService.Iface.class, "hello", "Armeria");
        final HttpResponse res = HttpResponse.of(HttpStatus.OK);
        final RpcResponse rpcRes = RpcResponse.of("Hello, Armeria!");
        final ClientRequestContext ctx =
                ClientRequestContextBuilder.of(req)
                                           .endpoint(Endpoint.of("localhost", 8080))
                                           .build();

        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().requestContent(rpcReq, req);
        ctx.logBuilder().endRequest();

        @SuppressWarnings("unchecked")
        final Client<HttpRequest, HttpResponse> delegate = mock(Client.class);
        when(delegate.execute(any(), any())).thenReturn(res);

        final HttpTracingClient stub = new HttpTracingClient(delegate, tracing, remoteServiceName);

        // do invoke
        final HttpResponse actualRes = stub.execute(ctx, req);

        assertThat(actualRes).isEqualTo(res);

        verify(delegate, times(1)).execute(same(ctx), argThat(arg -> {
            final RequestHeaders headers = arg.headers();
            return headers.contains(HttpHeaderNames.of("x-b3-traceid")) &&
                   headers.contains(HttpHeaderNames.of("x-b3-spanid")) &&
                   headers.contains(HttpHeaderNames.of("x-b3-sampled"));
        }));

        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        ctx.logBuilder().responseFirstBytesTransferred();
        ctx.logBuilder().responseContent(rpcRes, res);
        ctx.logBuilder().endResponse();
    }
}
