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

package com.linecorp.armeria.server.tracing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;
import org.mockito.Matchers;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.KeyValueAnnotation;
import com.github.kristofa.brave.Sampler;
import com.github.kristofa.brave.SpanId;
import com.github.kristofa.brave.TraceData;
import com.twitter.zipkin.gen.Annotation;
import com.twitter.zipkin.gen.Span;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.tracing.StubCollector;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

import io.netty.channel.Channel;

public class TracingServiceTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_METHOD = "hello";

    @Test
    public void shouldSubmitSpanWhenRequestIsSampled() throws Exception {
        StubCollector spanCollector = testServiceInvocation(true /* sampled */);

        // only one span should be submitted
        assertThat(spanCollector.spans, hasSize(1));

        // check span name
        Span span = spanCollector.spans.get(0);
        assertThat(span.getName(), is(TEST_METHOD));

        // check # of annotations
        List<Annotation> annotations = span.getAnnotations();
        assertThat(annotations, hasSize(2));

        // check annotation values
        List<String> values = annotations.stream().map(anno -> anno.value).collect(Collectors.toList());
        assertThat(values, is(containsInAnyOrder("sr", "ss")));

        // check service name
        List<String> serviceNames = annotations.stream()
                                               .map(anno -> anno.host.service_name)
                                               .collect(Collectors.toList());
        assertThat(serviceNames, is(contains(TEST_SERVICE, TEST_SERVICE)));
    }

    @Test
    public void shouldNotSubmitSpanWhenRequestIsNotSampled() throws Exception {
        StubCollector spanCollector = testServiceInvocation(false /* not sampled */);

        // don't submit any spans
        assertThat(spanCollector.spans, hasSize(0));
    }

    private static StubCollector testServiceInvocation(boolean sampled) throws Exception {
        StubCollector spanCollector = new StubCollector();

        Brave brave = new Brave.Builder(TEST_SERVICE)
                .spanCollector(spanCollector)
                .traceSampler(Sampler.create(1.0f))
                .build();

        @SuppressWarnings("unchecked")
        Service<ThriftCall, ThriftReply> delegate = mock(Service.class);

        TraceData traceData = TraceData.builder()
                                       .sample(sampled)
                                       .spanId(SpanId.builder().traceId(1).spanId(2).parentId(3L).build())
                                       .build();

        TracingServiceImpl stub = new TracingServiceImpl(delegate, brave, traceData);

        ThriftCall req = new ThriftCall(0, HelloService.Iface.class, "hello", "trustin");
        DefaultRequestLog reqLog = new DefaultRequestLog();
        reqLog.start(mock(Channel.class), SessionProtocol.H2C, "localhost", TEST_METHOD, "/");
        reqLog.end();

        ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        // AbstractTracingService prefers RpcRequest.method() to ctx.method(), so "POST" should be ignored.
        when(ctx.method()).thenReturn("POST");
        when(ctx.requestLogFuture()).thenReturn(reqLog);
        ctx.onEnter(Matchers.isA(Runnable.class));
        ctx.onExit(Matchers.isA(Runnable.class));

        ThriftReply res = new ThriftReply(0, "Hello, trustin!");
        when(delegate.serve(ctx, req)).thenReturn(res);

        // do invoke
        stub.serve(ctx, req);

        verify(delegate, times(1)).serve(eq(ctx), eq(req));
        return spanCollector;
    }

    private static class TracingServiceImpl extends AbstractTracingService<ThriftCall, ThriftReply> {

        private final TraceData traceData;

        TracingServiceImpl(Service<ThriftCall, ThriftReply> delegate, Brave brave, TraceData traceData) {
            super(delegate, brave);
            this.traceData = traceData;
        }

        @Override
        protected TraceData getTraceData(ServiceRequestContext ctx, ThriftCall req) {
            return traceData;
        }

        @Override
        protected List<KeyValueAnnotation> annotations(ServiceRequestContext ctx, RequestLog req,
                                                       ThriftReply res) {
            return Collections.emptyList();
        }
    }
}
