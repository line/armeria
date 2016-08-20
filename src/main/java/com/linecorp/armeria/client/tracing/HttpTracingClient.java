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

import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.PARENT_SPAN_ID;
import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.SAMPLED;
import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.SPAN_ID;
import static com.linecorp.armeria.internal.tracing.BraveHttpHeaderNames.TRACE_ID;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.IdConversion;
import com.github.kristofa.brave.SpanId;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.http.DefaultHttpHeaders;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;

/**
 * Decorates a {@link Client} to trace outbound {@link HttpRequest}s using
 * <a href="http://zipkin.io/">Zipkin</a>.
 * <p>
 * This decorator puts trace data into HTTP headers. The specifications of header names and its values
 * correspond to <a href="http://zipkin.io/">Zipkin</a>.
 */
public class HttpTracingClient extends AbstractTracingClient<HttpRequest, HttpResponse> {

    /**
     * Creates a new tracing {@link Client} decorator using the specified {@link Brave} instance.
     */
    public static Function<Client<? super HttpRequest, ? extends HttpResponse>, HttpTracingClient>
    newDecorator(Brave brave) {
        return delegate -> new HttpTracingClient(delegate, brave);
    }

    HttpTracingClient(Client<? super HttpRequest, ? extends HttpResponse> delegate, Brave brave) {
        super(delegate, brave);
    }

    @Override
    protected void putTraceData(ClientRequestContext ctx, HttpRequest req, @Nullable SpanId spanId) {
        final HttpHeaders headers;
        if (ctx.hasAttr(ClientRequestContext.HTTP_HEADERS)) {
            headers = ctx.attr(ClientRequestContext.HTTP_HEADERS).get();
        } else {
            headers = new DefaultHttpHeaders(true);
            ctx.attr(ClientRequestContext.HTTP_HEADERS).set(headers);
        }

        if (spanId == null) {
            headers.add(SAMPLED, "0");
        } else {
            headers.add(SAMPLED, "1");
            headers.add(TRACE_ID, IdConversion.convertToString(spanId.traceId));
            headers.add(SPAN_ID, IdConversion.convertToString(spanId.spanId));
            if (!spanId.root()) {
                headers.add(PARENT_SPAN_ID, IdConversion.convertToString(spanId.parentId));
            }
        }
    }
}
