/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.server.brave;

import static com.linecorp.armeria.server.brave.BraveService.SERVICE_REQUEST_DECORATING_SCOPE;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;

abstract class AbstractBraveService<BI extends brave.Request, I extends Request, O extends Response>
        extends AbstractUnwrappable<Service<I, O>> {

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    AbstractBraveService(Service<I, O> delegate) {
        super(delegate);
    }

    O serve0(ServiceRequestContext ctx, I req, BI braveReq, Span span) throws Exception {
        final RequestContextExtension ctxExtension = ctx.as(RequestContextExtension.class);
        if (currentTraceContext().scopeDecoratorAdded() && !span.isNoop() && ctxExtension != null) {
            // Run the scope decorators when the ctx is pushed to the thread local.
            ctxExtension.hook(() -> currentTraceContext().decorateScope(span.context(),
                                                                        SERVICE_REQUEST_DECORATING_SCOPE));
        }

        maybeAddTagsToSpan(ctx, braveReq, span);
        try (SpanInScope ignored = tracer().withSpanInScope(span)) {
            return unwrap().serve(ctx, req);
        }
    }

    abstract Tracer tracer();

    abstract RequestContextCurrentTraceContext currentTraceContext();

    abstract void maybeAddTagsToSpan(ServiceRequestContext ctx, BI braveReq, Span span);
}
