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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.KeyValueAnnotation;
import com.github.kristofa.brave.ServerRequestAdapter;
import com.github.kristofa.brave.ServerResponseAdapter;
import com.github.kristofa.brave.ServerSpan;
import com.github.kristofa.brave.TraceData;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.server.DecoratingServiceInvocationHandler;
import com.linecorp.armeria.server.ServiceInvocationHandler;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

/**
 * An abstract {@link ServiceInvocationHandler} that traces service invocations.
 * <p>
 * This class depends on <a href="https://github.com/openzipkin/brave">Brave</a> distributed tracing library.
 */
public abstract class TracingServiceInvocationHandler extends DecoratingServiceInvocationHandler {

    private final ServerTracingInterceptor serverInterceptor;

    protected TracingServiceInvocationHandler(ServiceInvocationHandler handler, Brave brave) {
        super(handler);
        serverInterceptor = new ServerTracingInterceptor(brave);
    }

    @Override
    public final void invoke(ServiceInvocationContext ctx,
                             Executor blockingTaskExecutor,
                             Promise<Object> promise) throws Exception {

        final TraceData traceData = getTraceData(ctx);

        final ServerRequestAdapter requestAdapter = new InternalServerRequestAdapter(ctx.method(), traceData);

        final ServerSpan span = serverInterceptor.openSpan(requestAdapter);
        if (span != null && span.getSample()) {
            ctx.onEnter(() -> serverInterceptor.setSpan(span))
               .onExit(serverInterceptor::clearSpan);
            promise.addListener(future -> {
                serverInterceptor.closeSpan(span, createResponseAdapter(ctx, future));
            });
        }
        try {
            super.invoke(ctx, blockingTaskExecutor, promise);
        } finally {
            serverInterceptor.clearSpan();
        }
    }

    /**
     * Gets a {@link TraceData} from the specified {@link ServiceInvocationContext}.
     *
     * @return the {@link TraceData}.
     */
    protected abstract TraceData getTraceData(ServiceInvocationContext ctx);

    /**
     * Returns server side annotations that should be added to span.
     */
    protected <T> List<KeyValueAnnotation> annotations(ServiceInvocationContext ctx, Future<? super T> result) {
        final List<KeyValueAnnotation> annotations = new ArrayList<>(5);

        final StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(ctx.scheme() != null ? ctx.scheme().uriText() : "unknown");
        uriBuilder.append("://");
        uriBuilder.append(ctx.host() != null ? ctx.host() : "<unknown-host>");
        uriBuilder.append(ctx.path() != null ? ctx.path() : "/<unknown-path>");
        if (ctx.method() != null) {
            uriBuilder.append('#');
            uriBuilder.append(ctx.method());
        }
        annotations.add(KeyValueAnnotation.create("server.uri", uriBuilder.toString()));

        if (ctx.remoteAddress() != null) {
            annotations.add(KeyValueAnnotation.create("server.remote", ctx.remoteAddress().toString()));
        }

        if (ctx.localAddress() != null) {
            annotations.add(KeyValueAnnotation.create("server.local", ctx.localAddress().toString()));
        }

        if (result != null && result.isDone()) {
            final String resultText = result.isSuccess() ? "success" : "failure";
            annotations.add(KeyValueAnnotation.create("server.result", resultText));

            if (result.cause() != null) {
                annotations.add(KeyValueAnnotation.create("server.cause", result.cause().getMessage()));
            }
        }
        return annotations;
    }

    protected <T> ServerResponseAdapter createResponseAdapter(ServiceInvocationContext ctx,
                                                              Future<? super T> result) {

        final List<KeyValueAnnotation> annotations = annotations(ctx, result);
        return new ServerResponseAdapter() {
            @Override
            public Collection<KeyValueAnnotation> responseAnnotations() {
                return annotations;
            }
        };
    }

    /**
     * A {@link ServerRequestAdapter} holding span name and {@link TraceData} that will be passed to brave.
     */
    private static class InternalServerRequestAdapter implements ServerRequestAdapter {

        private final String spanName;

        private final TraceData traceData;

        public InternalServerRequestAdapter(String spanName, TraceData traceData) {
            this.spanName = spanName;
            this.traceData = traceData;
        }

        @Override
        public TraceData getTraceData() {
            return traceData;
        }

        @Override
        public String getSpanName() {
            return spanName;
        }

        @Override
        public Collection<KeyValueAnnotation> requestAnnotations() {
            return Collections.emptyList();
        }
    }

}
