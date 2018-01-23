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

package com.linecorp.armeria.server.tracing;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.tracing.AsciiStringKeyFactory;
import com.linecorp.armeria.internal.tracing.SpanTags;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

import brave.Span;
import brave.Span.Kind;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import io.netty.util.concurrent.FastThreadLocal;

/**
 * Decorates a {@link Service} to trace inbound {@link HttpRequest}s using
 * <a href="http://zipkin.io/">Zipkin</a>.
 *
 * <p>This decorator retrieves trace data from HTTP headers. The specifications of header names and its values
 * correspond to <a href="http://zipkin.io/">Zipkin</a>.
 */
public class HttpTracingService extends SimpleDecoratingService<HttpRequest, HttpResponse> {

    private static final FastThreadLocal<SpanInScope> SPAN_IN_THREAD = new FastThreadLocal<>();

    /**
     * Creates a new tracing {@link Service} decorator using the specified {@link Tracing} instance.
     */
    public static Function<Service<HttpRequest, HttpResponse>, HttpTracingService>
    newDecorator(Tracing tracing) {
        return service -> new HttpTracingService(service, tracing);
    }

    private final Tracer tracer;
    private final TraceContext.Extractor<HttpHeaders> extractor;

    /**
     * Creates a new instance.
     */
    public HttpTracingService(Service<HttpRequest, HttpResponse> delegate, Tracing tracing) {
        super(delegate);
        tracer = tracing.tracer();
        extractor = tracing.propagationFactory().create(AsciiStringKeyFactory.INSTANCE)
                           .extractor(HttpHeaders::get);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        TraceContextOrSamplingFlags contextOrFlags = extractor.extract(req.headers());
        Span span = contextOrFlags.context() != null ? tracer.joinSpan(contextOrFlags.context())
                                                     : tracer.newTrace(contextOrFlags.samplingFlags());
        // For no-op spans, nothing special to do.
        if (span.isNoop()) {
            return delegate().serve(ctx, req);
        }

        final String method = ctx.method().name();
        span.kind(Kind.SERVER).name(method).start();

        ctx.onEnter(unused -> SPAN_IN_THREAD.set(tracer.withSpanInScope(span)));
        ctx.onExit(unused -> {
            SpanInScope spanInScope = SPAN_IN_THREAD.get();
            if (spanInScope != null) {
                spanInScope.close();
                SPAN_IN_THREAD.remove();
            }
        });

        ctx.log().addListener(log -> closeSpan(span, log), RequestLogAvailability.COMPLETE);

        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return delegate().serve(ctx, req);
        }
    }

    private void closeSpan(Span span, RequestLog log) {
        SpanTags.addTags(span, log);
        span.finish();
    }
}
