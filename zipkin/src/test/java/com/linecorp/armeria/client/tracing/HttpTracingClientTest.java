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

import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.common.tracing.HelloService;
import com.linecorp.armeria.common.tracing.SpanCollectingReporter;

import brave.Tracing;
import brave.sampler.Sampler;
import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoop;
import zipkin2.Span;
import zipkin2.Span.Kind;

public class HttpTracingClientTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_SPAN = "hello";

    @After
    public void tearDown() {
        Tracing.current().close();
    }

    @Test(timeout = 20000)
    public void shouldSubmitSpanWhenSampled() throws Exception {
        SpanCollectingReporter reporter = new SpanCollectingReporter();

        Tracing tracing = Tracing.newBuilder()
                                 .localServiceName(TEST_SERVICE)
                                 .spanReporter(reporter)
                                 .sampler(Sampler.create(1.0f))
                                 .build();
        testRemoteInvocation(tracing, null);

        // check span name
        Span span = reporter.spans().take();
        assertThat(span.name()).isEqualTo(TEST_SPAN);

        // check kind
        assertThat(span.kind() == Kind.CLIENT);

        // only one span should be submitted
        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();

        // check # of annotations (zipkin2 format does not use them by default)
        assertThat(span.annotations()).isEmpty();

        // check tags
        assertThat(span.tags()).containsAllEntriesOf(ImmutableMap.of(
                "http.host", "localhost",
                "http.method", "POST",
                "http.path", "/hello/armeria",
                "http.status_code", "200",
                "http.url", "none+h2c://localhost/hello/armeria"));

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);

        // check remote service name
        assertThat(span.remoteServiceName()).isEqualTo("localhost");
    }

    @Test(timeout = 20000)
    public void shouldSubmitSpanWithCustomRemoteName() throws Exception {
        SpanCollectingReporter reporter = new SpanCollectingReporter();

        Tracing tracing = Tracing.newBuilder()
                                 .localServiceName(TEST_SERVICE)
                                 .spanReporter(reporter)
                                 .sampler(Sampler.create(1.0f))
                                 .build();
        testRemoteInvocation(tracing, "foo");

        // check span name
        Span span = reporter.spans().take();

        // check tags
        assertThat(span.tags()).containsAllEntriesOf(ImmutableMap.of(
                "http.host", "localhost",
                "http.method", "POST",
                "http.path", "/hello/armeria",
                "http.status_code", "200",
                "http.url", "none+h2c://localhost/hello/armeria"));

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);

        // check remote service name
        assertThat(span.remoteServiceName()).isEqualTo("foo");
    }

    @Test
    public void shouldNotSubmitSpanWhenNotSampled() throws Exception {
        SpanCollectingReporter reporter = new SpanCollectingReporter();
        Tracing tracing = Tracing.newBuilder()
                                 .localServiceName(TEST_SERVICE)
                                 .spanReporter(reporter)
                                 .sampler(Sampler.create(0.0f))
                                 .build();
        testRemoteInvocation(tracing, null);

        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    private static void testRemoteInvocation(Tracing tracing, String remoteServiceName)
            throws Exception {

        // prepare parameters
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/hello/armeria");
        final RpcRequest rpcReq = RpcRequest.of(HelloService.Iface.class, "hello", "Armeria");
        final HttpResponse res = HttpResponse.of(HttpStatus.OK);
        final RpcResponse rpcRes = RpcResponse.of("Hello, Armeria!");
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                new DefaultEventLoop(), NoopMeterRegistry.get(), H2C, Endpoint.of("localhost", 8080),
                HttpMethod.POST, "/hello/armeria", null, null, ClientOptions.DEFAULT, req);

        ctx.logBuilder().startRequest(mock(Channel.class), H2C, "localhost");
        ctx.logBuilder().requestContent(rpcReq, req);
        ctx.logBuilder().endRequest();

        @SuppressWarnings("unchecked")
        Client<HttpRequest, HttpResponse> delegate = mock(Client.class);
        when(delegate.execute(any(), any())).thenReturn(res);

        HttpTracingClient stub = new HttpTracingClient(delegate, tracing, remoteServiceName);

        // do invoke
        HttpResponse actualRes = stub.execute(ctx, req);

        assertThat(actualRes).isEqualTo(res);

        verify(delegate, times(1)).execute(ctx, req);

        ctx.logBuilder().responseHeaders(HttpHeaders.of(HttpStatus.OK));
        ctx.logBuilder().responseContent(rpcRes, res);
        ctx.logBuilder().endResponse();
    }
}
