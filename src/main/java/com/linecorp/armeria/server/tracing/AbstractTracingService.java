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

import static com.linecorp.armeria.common.util.Functions.voidFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.KeyValueAnnotation;
import com.github.kristofa.brave.ServerRequestAdapter;
import com.github.kristofa.brave.ServerResponseAdapter;
import com.github.kristofa.brave.ServerSpan;
import com.github.kristofa.brave.TraceData;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An abstract {@link DecoratingService} that traces incoming {@link Request}s.
 * <p>
 * This class depends on <a href="https://github.com/openzipkin/brave">Brave</a>, a distributed tracing library.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractTracingService<I extends Request, O extends Response>
        extends DecoratingService<I, O, I, O> {

    private final ServerTracingInterceptor serverInterceptor;

    /**
     * Creates a new instance.
     */
    protected AbstractTracingService(Service<? super I, ? extends O> delegate, Brave brave) {
        super(delegate);
        serverInterceptor = new ServerTracingInterceptor(brave);
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        final TraceData traceData = getTraceData(ctx, req);
        final String method = req instanceof RpcRequest ? ((RpcRequest) req).method() : ctx.method();
        final ServerRequestAdapter requestAdapter = new InternalServerRequestAdapter(method, traceData);

        final ServerSpan serverSpan = serverInterceptor.openSpan(requestAdapter);
        final boolean sampled;
        if (serverSpan != null) {
            ctx.onEnter(() -> serverInterceptor.setSpan(serverSpan));
            ctx.onExit(serverInterceptor::clearSpan);
            sampled = serverSpan.getSample();
        } else {
            sampled = false;
        }

        try {
            final O res = delegate().serve(ctx, req);
            if (sampled) {
                ctx.requestLogFuture().thenAcceptBoth(
                        res.closeFuture(),
                        (log, unused) -> closeSpan(ctx, serverSpan, log, res))
                   .exceptionally(CompletionActions::log);
            }
            return res;
        } finally {
            serverInterceptor.clearSpan();
        }
    }

    /**
     * Gets a {@link TraceData} from the specified {@link Request} or {@link ServiceRequestContext}.
     *
     * @return the {@link TraceData}.
     */
    protected abstract TraceData getTraceData(ServiceRequestContext ctx, I req);

    /**
     * Returns the server-side annotations that should be added to a Zipkin span.
     */
    protected List<KeyValueAnnotation> annotations(
            ServiceRequestContext ctx, RequestLog req, O res) {

        final List<KeyValueAnnotation> annotations = new ArrayList<>(5);

        final StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(req.scheme().uriText());
        uriBuilder.append("://");
        uriBuilder.append(req.host());
        uriBuilder.append(ctx.path());
        if (req.method() != null) {
            uriBuilder.append('#');
            uriBuilder.append(req.method());
        }
        annotations.add(KeyValueAnnotation.create("server.uri", uriBuilder.toString()));

        if (ctx.remoteAddress() != null) {
            annotations.add(KeyValueAnnotation.create("server.remote", ctx.remoteAddress().toString()));
        }

        if (ctx.localAddress() != null) {
            annotations.add(KeyValueAnnotation.create("server.local", ctx.localAddress().toString()));
        }

        final CompletableFuture<?> f = res.closeFuture();
        if (f.isDone()) {
            // Need to use a callback because CompletableFuture does not have a getter for the cause of failure.
            // The callback will be invoked immediately because the future is done already.
            f.handle(voidFunction((result, cause) -> {
                final String resultText = cause == null ? "success" : "failure";
                annotations.add(KeyValueAnnotation.create("server.result", resultText));

                if (cause != null) {
                    annotations.add(KeyValueAnnotation.create("server.cause", cause.toString()));
                }
            })).exceptionally(CompletionActions::log);
        }

        return annotations;
    }

    private void closeSpan(ServiceRequestContext ctx, ServerSpan serverSpan, RequestLog req, O res) {
        if (req.hasAttr(RequestLog.RPC_REQUEST)) {
            serverSpan.getSpan().setName(req.attr(RequestLog.RPC_REQUEST).get().method());
        }
        serverInterceptor.closeSpan(serverSpan, createResponseAdapter(ctx, req, res));
    }

    /**
     * Creates a new {@link ServerResponseAdapter} from the specified request-response information.
     */
    protected ServerResponseAdapter createResponseAdapter(
            ServiceRequestContext ctx, RequestLog req, O res) {
        final List<KeyValueAnnotation> annotations = annotations(ctx, req, res);
        return () -> annotations;
    }

    /**
     * A {@link ServerRequestAdapter} holding span name and {@link TraceData} that will be passed to brave.
     */
    private static class InternalServerRequestAdapter implements ServerRequestAdapter {

        private final String spanName;

        private final TraceData traceData;

        InternalServerRequestAdapter(String spanName, TraceData traceData) {
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
