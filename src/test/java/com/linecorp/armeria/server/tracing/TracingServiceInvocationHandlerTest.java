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
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.junit.Test;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.KeyValueAnnotation;
import com.github.kristofa.brave.Sampler;
import com.github.kristofa.brave.SpanId;
import com.github.kristofa.brave.TraceData;
import com.twitter.zipkin.gen.Annotation;
import com.twitter.zipkin.gen.Span;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.tracing.TracingTestBase;
import com.linecorp.armeria.server.ServiceInvocationHandler;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

public class TracingServiceInvocationHandlerTest extends TracingTestBase {

    private static final String TEST_SERVICE = "testservice";

    private static final String TEST_SPAN = "testspan";

    @Test
    public void shouldSubmitSpanWhenRequestIsSampled() throws Exception {
        StubCollector spanCollector = testServiceInvocation(true /* sampled */);

        // only one span should be submitted
        assertThat(spanCollector.spans, hasSize(1));

        // check span name
        Span span = spanCollector.spans.get(0);
        assertThat(span.name, is(TEST_SPAN));

        // check # of annotations
        List<Annotation> annotations = span.annotations;
        assertThat(annotations, hasSize(2));

        // check annotation values
        List<String> values = annotations.stream().map(anno -> anno.value).collect(Collectors.toList());
        assertThat(values, is(containsInAnyOrder("sr", "ss")));

        // check service name
        List<String> serviceNames = annotations.stream()
                                               .map(anno -> anno.getHost().getService_name())
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

        ServiceInvocationHandler serviceInvocationHandler = mock(ServiceInvocationHandler.class);

        TraceData traceData = TraceData.builder().sample(sampled).spanId(SpanId.create(1, 2, 3L)).build();

        TracingServiceInvocationHandlerImpl stub = new TracingServiceInvocationHandlerImpl(
                serviceInvocationHandler, brave, traceData);

        ServiceInvocationContext ctx = mock(ServiceInvocationContext.class);
        when(ctx.method()).thenReturn(TEST_SPAN);
        Executor executor = mock(Executor.class);
        Promise<Object> promise = mockPromise();

        // do invoke
        stub.invoke(ctx, executor, promise);

        verify(serviceInvocationHandler, times(1)).invoke(eq(ctx), eq(executor), eq(promise));
        return spanCollector;
    }

    private static class TracingServiceInvocationHandlerImpl extends TracingServiceInvocationHandler {

        private final TraceData traceData;

        public TracingServiceInvocationHandlerImpl(ServiceInvocationHandler serviceInvocationHandler,
                                                   Brave brave,
                                                   TraceData traceData) {
            super(serviceInvocationHandler, brave);
            this.traceData = traceData;
        }

        @Override
        protected TraceData getTraceData(ServiceInvocationContext ctx) {
            return traceData;
        }

        @Override
        protected <T> List<KeyValueAnnotation> annotations(ServiceInvocationContext ctx,
                                                           @Nullable Future<? super T> result) {
            return Collections.emptyList();
        }
    }

}
