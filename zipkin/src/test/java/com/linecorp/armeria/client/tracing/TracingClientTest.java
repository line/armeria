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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.tracing.HelloService;
import com.linecorp.armeria.common.tracing.SpanCollectingReporter;

import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoop;
import zipkin.Annotation;
import zipkin.Span;

public class TracingClientTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_SPAN = "hello";

    @Test
    public void shouldSubmitSpanWhenSampled() throws Exception {
        SpanCollectingReporter reporter = testRemoteInvocationWithSamplingRate(1.0f);

        // only one span should be submitted
        assertThat(reporter.spans(), hasSize(1));

        // check span name
        Span span = reporter.spans().get(0);
        assertThat(span.name, is(TEST_SPAN));

        // check # of annotations
        List<Annotation> annotations = span.annotations;
        assertThat(annotations, hasSize(2));

        // check annotation values
        List<String> values = annotations.stream().map(anno -> anno.value).collect(Collectors.toList());
        assertThat(values, is(containsInAnyOrder("cs", "cr")));

        // check service name
        List<String> serviceNames = annotations.stream()
                                               .map(anno -> anno.endpoint.serviceName)
                                               .collect(Collectors.toList());
        assertThat(serviceNames, is(contains(TEST_SERVICE, TEST_SERVICE)));
    }

    @Test
    public void shouldNotSubmitSpanWhenNotSampled() throws Exception {
        SpanCollectingReporter reporter = testRemoteInvocationWithSamplingRate(0.0f);

        assertThat(reporter.spans(), hasSize(0));
    }

    private static SpanCollectingReporter testRemoteInvocationWithSamplingRate(
            float samplingRate) throws Exception {

        SpanCollectingReporter reporter = new SpanCollectingReporter();

        Brave brave = new Brave.Builder(TEST_SERVICE)
                .reporter(reporter)
                .traceSampler(Sampler.create(samplingRate))
                .build();

        // prepare parameters
        final ThriftCall req = new ThriftCall(0, HelloService.Iface.class, "hello", "Armeria");
        final ThriftReply res = new ThriftReply(0, "Hello, Armeria!");
        final ClientRequestContext ctx = new DefaultClientRequestContext(
                new DefaultEventLoop(), SessionProtocol.H2C, Endpoint.of("localhost", 8080),
                "POST", "/", "", ClientOptions.DEFAULT, req);

        ctx.requestLogBuilder().start(mock(Channel.class), SessionProtocol.H2C, "localhost", "POST", "/");
        ctx.requestLogBuilder().end();

        @SuppressWarnings("unchecked")
        Client<ThriftCall, ThriftReply> delegate = mock(Client.class);
        when(delegate.execute(any(), any())).thenReturn(res);

        TracingClientImpl stub = new TracingClientImpl(delegate, brave);

        // do invoke
        ThriftReply actualRes = stub.execute(ctx, req);

        assertThat(actualRes, is(res));

        verify(delegate, times(1)).execute(ctx, req);

        return reporter;
    }

    private static class TracingClientImpl extends AbstractTracingClient<ThriftCall, ThriftReply> {

        TracingClientImpl(Client<ThriftCall, ThriftReply> delegate, Brave brave) {
            super(delegate, brave);
        }

        @Override
        protected void putTraceData(ClientRequestContext ctx, ThriftCall req, @Nullable SpanId spanId) {}
    }
}
