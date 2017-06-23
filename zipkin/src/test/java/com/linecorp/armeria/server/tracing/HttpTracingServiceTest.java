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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.TraceData;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.tracing.HttpTracingTestBase;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

public class HttpTracingServiceTest extends HttpTracingTestBase {

    private static final HttpTracingService serviceInvocationHandler =
            new HttpTracingService(mock(Service.class), mock(Brave.class));

    @Test
    public void testGetTraceData() {
        final HttpRequest httpRequest = HttpRequest.of(HttpHeaders.of(HttpMethod.GET, "/")
                                                                  .add(traceHeaders()));

        ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        when(ctx.request()).thenReturn(httpRequest);

        TraceData traceData = serviceInvocationHandler.getTraceData(ctx, httpRequest);
        assertThat(traceData.getSpanId(), is(testSpanId));
        assertThat(traceData.getSample(), is(true));
    }

    @Test
    public void testGetTraceDataIfRequestDoesNotContainTraceData() {
        final HttpRequest httpRequest = HttpRequest.of(HttpMethod.GET, "/");

        ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        when(ctx.request()).thenReturn(httpRequest);

        TraceData traceData = serviceInvocationHandler.getTraceData(ctx, httpRequest);
        assertThat(traceData.getSample(), is(nullValue()));
        assertThat(traceData.getSpanId(), is(nullValue()));
    }

    @Test
    public void testGetTraceDataIfRequestIsNotSampled() {
        testGetTraceDataIfRequestIsNotSampled(traceHeadersNotSampled());
        testGetTraceDataIfRequestIsNotSampled(traceHeadersNotSampledFalse());
        testGetTraceDataIfRequestIsNotSampled(traceHeadersNotSampledFalseUpperCase());
    }

    private static void testGetTraceDataIfRequestIsNotSampled(HttpHeaders headers) {
        final HttpRequest httpRequest = HttpRequest.of(HttpHeaders.of(HttpMethod.GET, "/")
                                                                  .add(headers));

        ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        when(ctx.request()).thenReturn(httpRequest);

        TraceData traceData = serviceInvocationHandler.getTraceData(ctx, httpRequest);
        assertThat(traceData.getSpanId(), is(nullValue()));
        assertThat(traceData.getSample(), is(false));
    }
}
