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

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.IdConversion;
import com.github.kristofa.brave.SpanId;
import com.github.kristofa.brave.TraceData;
import com.github.kristofa.brave.http.BraveHttpHeaders;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.server.ServiceInvocationHandler;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;

/**
 * A {@link TracingServiceInvocationHandler} that uses HTTP headers as a container of trace data.
 */
class HttpTracingServiceInvocationHandler extends TracingServiceInvocationHandler {

    HttpTracingServiceInvocationHandler(ServiceInvocationHandler handler, Brave brave) {
        super(handler, brave);
    }

    @Override
    protected TraceData getTraceData(ServiceInvocationContext ctx) {
        final Object request = ctx.originalRequest();
        if (!(request instanceof HttpRequest)) {
            return TraceData.builder().build();
        }

        final HttpHeaders headers = ((HttpMessage) request).headers();

        // The following HTTP trace header spec is based on
        // com.github.kristofa.brave.http.HttpServerRequestAdapter#getTraceData

        final String sampled = headers.get(BraveHttpHeaders.Sampled.getName());
        if (sampled == null) {
            // trace data is not specified
            return TraceData.builder().build();
        }
        if ("0".equals(sampled) || "false".equalsIgnoreCase(sampled)) {
            // this request is not sampled
            return TraceData.builder().sample(false).build();
        }

        final String traceId = headers.get(BraveHttpHeaders.TraceId.getName());
        final String spanId = headers.get(BraveHttpHeaders.SpanId.getName());
        if (traceId == null || spanId == null) {
            // broken trace header
            return TraceData.builder().build();
        }

        // parentSpanId can be null
        final String parentSpanId = headers.get(BraveHttpHeaders.ParentSpanId.getName());

        // create new SpanId instance
        final SpanId span =
                SpanId.builder()
                      .traceId(IdConversion.convertToLong(traceId))
                      .spanId(IdConversion.convertToLong(spanId))
                      .parentId(parentSpanId == null ? null : IdConversion.convertToLong(parentSpanId))
                      .sampled(true)
                      .build();

        return TraceData.builder().sample(true).spanId(span).build();
    }

}
