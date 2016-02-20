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
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.Sampler;
import com.github.kristofa.brave.SpanId;
import com.twitter.zipkin.gen.Annotation;
import com.twitter.zipkin.gen.Span;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.RemoteInvoker;
import com.linecorp.armeria.common.tracing.TracingTestBase;

import io.netty.util.concurrent.Future;

public class TracingRemoteInvokerTest extends TracingTestBase {

    private static final String TEST_SERVICE = "testservice";

    private static final String TEST_SPAN = "serve";

    @Test
    public void shouldSubmitSpanWhenSampled() throws Exception {
        StubCollector spanCollector = testRemoteInvocationWithSamplingRate(1.0f);

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
        assertThat(values, is(containsInAnyOrder("cs", "cr")));

        // check service name
        List<String> serviceNames = annotations.stream()
                                               .map(anno -> anno.getHost().getService_name())
                                               .collect(Collectors.toList());
        assertThat(serviceNames, is(contains(TEST_SERVICE, TEST_SERVICE)));
    }

    @Test
    public void shouldNotSubmitSpanWhenNotSampled() throws Exception {
        StubCollector spanCollector = testRemoteInvocationWithSamplingRate(0.0f);

        assertThat(spanCollector.spans, hasSize(0));
    }

    private static StubCollector testRemoteInvocationWithSamplingRate(float samplingRate) throws Exception {
        StubCollector spanCollector = new StubCollector();

        Brave brave = new Brave.Builder(TEST_SERVICE)
                .spanCollector(spanCollector)
                .traceSampler(Sampler.create(samplingRate))
                .build();

        Future<Object> mockFut = mockFuture();

        RemoteInvoker remoteInvoker = mock(RemoteInvoker.class);
        when(remoteInvoker.invoke(any(), any(), any(), any(), any())).thenReturn(mockFut);

        TracingRemoteInvoker stub = new TracingRemoteInvokerImpl(remoteInvoker, brave);

        // prepare parameters
        URI uri = new URI("http://xxx");
        ClientOptions options = ClientOptions.of();
        ClientCodec codec = mock(ClientCodec.class);
        Method method = getServiceMethod();
        Object[] args = { "a", "b" };

        // do invoke
        Future<Object> resultFut = stub.invoke(uri, options, codec, method, args);

        assertThat(resultFut, is(mockFut));

        verify(remoteInvoker, times(1)).invoke(eq(uri), anyObject(), eq(codec), eq(method), eq(args));

        return spanCollector;
    }

    private static class TracingRemoteInvokerImpl extends TracingRemoteInvoker {

        TracingRemoteInvokerImpl(RemoteInvoker remoteInvoker, Brave brave) {
            super(remoteInvoker, brave);
        }

        @Override
        protected ClientOptions putTraceData(ClientOptions baseOptions, SpanId spanId) {
            return null;
        }
    }

}
