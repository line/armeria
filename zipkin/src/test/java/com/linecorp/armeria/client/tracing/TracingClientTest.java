/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.junit.Test;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.Sampler;
import com.github.kristofa.brave.SpanId;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.tracing.HelloService;
import com.linecorp.armeria.common.tracing.SpanCollectingReporter;

import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoop;
import zipkin.Annotation;
import zipkin.Span;

public class TracingClientTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_SPAN = "hello";

    @Test(timeout = 10000)
    public void shouldSubmitSpanWhenSampled() throws Exception {
        SpanCollectingReporter reporter = testRemoteInvocationWithSamplingRate(1.0f);

        // check span name
        Span span = reporter.spans().take();
        assertThat(span.name).isEqualTo(TEST_SPAN);

        // only one span should be submitted
        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();

        // check # of annotations
        List<Annotation> annotations = span.annotations;
        assertThat(annotations).hasSize(2);

        // check annotation values
        List<String> values = annotations.stream().map(anno -> anno.value).collect(Collectors.toList());
        assertThat(values).containsExactlyInAnyOrder("cs", "cr");

        // check service name
        List<String> serviceNames = annotations.stream()
                                               .map(anno -> anno.endpoint.serviceName)
                                               .collect(Collectors.toList());
        assertThat(serviceNames).containsExactly(TEST_SERVICE, TEST_SERVICE);
    }

    @Test
    public void shouldNotSubmitSpanWhenNotSampled() throws Exception {
        SpanCollectingReporter reporter = testRemoteInvocationWithSamplingRate(0.0f);

        assertThat(reporter.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    private static SpanCollectingReporter testRemoteInvocationWithSamplingRate(
            float samplingRate) throws Exception {

        SpanCollectingReporter reporter = new SpanCollectingReporter();

        Brave brave = new Brave.Builder(TEST_SERVICE)
                .reporter(reporter)
                .traceSampler(Sampler.create(samplingRate))
                .build();

        // prepare parameters
        final RpcRequest req = RpcRequest.of(HelloService.Iface.class, "hello", "Armeria");
        final RpcResponse res = RpcResponse.of("Hello, Armeria!");
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                new DefaultEventLoop(), H2C, Endpoint.of("localhost", 8080),
                HttpMethod.POST, "/", null, null, ClientOptions.DEFAULT, req);

        ctx.logBuilder().startRequest(mock(Channel.class), H2C, "localhost");
        ctx.logBuilder().endRequest();

        @SuppressWarnings("unchecked")
        Client<RpcRequest, RpcResponse> delegate = mock(Client.class);
        when(delegate.execute(any(), any())).thenReturn(res);

        TracingClientImpl stub = new TracingClientImpl(delegate, brave);

        // do invoke
        RpcResponse actualRes = stub.execute(ctx, req);

        assertThat(actualRes).isEqualTo(res);

        verify(delegate, times(1)).execute(ctx, req);

        ctx.logBuilder().endResponse();
        return reporter;
    }

    private static class TracingClientImpl extends AbstractTracingClient<RpcRequest, RpcResponse> {

        TracingClientImpl(Client<RpcRequest, RpcResponse> delegate, Brave brave) {
            super(delegate, brave);
        }

        @Override
        protected void putTraceData(ClientRequestContext ctx, RpcRequest req, @Nullable SpanId spanId) {}
    }
}
