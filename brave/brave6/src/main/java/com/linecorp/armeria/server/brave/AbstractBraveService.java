/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.brave;

import static com.linecorp.armeria.server.brave.ArmeriaServerParser.annotateWireSpan;
import static com.linecorp.armeria.server.brave.BraveService.SERVICE_REQUEST_DECORATING_SCOPE;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.internal.common.brave.TraceContextUtil;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;
import com.linecorp.armeria.server.TransientServiceOption;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;

abstract class AbstractBraveService<BI extends brave.Request, BO extends brave.Response,
        I extends Request, O extends Response> extends SimpleDecoratingService<I, O> {

    private final Tracer tracer;
    private final RequestContextCurrentTraceContext currentTraceContext;

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    protected AbstractBraveService(Service<I, O> delegate, Tracer tracer,
                                   RequestContextCurrentTraceContext currentTraceContext) {
        super(delegate);
        this.tracer = tracer;
        this.currentTraceContext = currentTraceContext;
    }

    @Override
    public final O serve(ServiceRequestContext ctx, I req) throws Exception {
        if (!ctx.config().transientServiceOptions().contains(TransientServiceOption.WITH_TRACING)) {
            return unwrap().serve(ctx, req);
        }
        final BI braveReq = braveRequest(ctx);
        final Span span = handleReceive(braveReq);
        TraceContextUtil.setTraceContext(ctx, span.context());

        final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);
        if (currentTraceContext.scopeDecoratorAdded() && !span.isNoop() && ctxExtension != null) {
            // Run the scope decorators when the ctx is pushed to the thread local.
            ctxExtension.hook(() -> currentTraceContext.decorateScope(span.context(),
                                                                        SERVICE_REQUEST_DECORATING_SCOPE));
        }

        maybeAddTagsToSpan(ctx, braveReq, span);
        try (SpanInScope ignored = tracer.withSpanInScope(span)) {
            return unwrap().serve(ctx, req);
        }
    }

    abstract BI braveRequest(ServiceRequestContext ctx);

    abstract BO braveResponse(ServiceRequestContext ctx, RequestLog log, BI braveReq);

    abstract Span handleReceive(BI braveReq);

    abstract void handleSend(BO response, Span span);

    void maybeAddTagsToSpan(ServiceRequestContext ctx, BI braveReq, Span span) {
        if (span.isNoop()) {
            // For no-op spans, nothing special to do.
            return;
        }

        ctx.log().whenComplete().thenAccept(log -> {
            annotateWireSpan(log, span);
            final BO braveRes = braveResponse(ctx, log, braveReq);
            handleSend(braveRes, span);
        });
    }
}
