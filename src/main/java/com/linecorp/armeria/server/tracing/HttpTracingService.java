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

import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.PARENT_SPAN_ID;
import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.SAMPLED;
import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.SPAN_ID;
import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.TRACE_ID;

import java.util.function.Function;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.IdConversion;
import com.github.kristofa.brave.SpanId;
import com.github.kristofa.brave.TraceData;

import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Decorates a {@link Service} to trace inbound {@link HttpRequest}s using
 * <a href="http://zipkin.io/">Zipkin</a>.
 * <p>
 * This decorator retrieves trace data from HTTP headers. The specifications of header names and its values
 * correspond to <a href="http://zipkin.io/">Zipkin</a>.
 */
public class HttpTracingService extends AbstractTracingService<HttpRequest, HttpResponse> {

    /**
     * Creates a new tracing {@link Service} decorator using the specified {@link Brave} instance.
     */
    public static Function<Service<? super HttpRequest, ? extends HttpResponse>,
                           HttpTracingService> newDecorator(Brave brave) {
        return service -> new HttpTracingService(service, brave);
    }

    HttpTracingService(Service<? super HttpRequest, ? extends HttpResponse> delegate, Brave brave) {
        super(delegate, brave);
    }

    @Override
    protected TraceData getTraceData(ServiceRequestContext ctx, HttpRequest req) {
        final HttpHeaders headers = req.headers();

        // The following HTTP trace header spec is based on
        // com.github.kristofa.brave.http.HttpServerRequestAdapter#getTraceData

        final String sampled = headers.get(SAMPLED);
        if (sampled == null) {
            // trace data is not specified
            return TraceData.builder().build();
        }
        if ("0".equals(sampled) || "false".equalsIgnoreCase(sampled)) {
            // this request is not sampled
            return TraceData.builder().sample(false).build();
        }

        final String traceId = headers.get(TRACE_ID);
        final String spanId = headers.get(SPAN_ID);
        if (traceId == null || spanId == null) {
            // broken trace header
            return TraceData.builder().build();
        }

        // parentSpanId can be null
        final String parentSpanId = headers.get(PARENT_SPAN_ID);

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
