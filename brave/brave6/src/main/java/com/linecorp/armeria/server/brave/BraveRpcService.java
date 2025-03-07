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

import static com.linecorp.armeria.internal.common.brave.TraceContextUtil.ensureScopeUsesRequestContext;
import static com.linecorp.armeria.server.brave.ArmeriaServerParser.annotateWireSpan;

import java.util.function.Function;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientServiceOption;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.rpc.RpcRequestParser;
import brave.rpc.RpcResponseParser;
import brave.rpc.RpcServerHandler;
import brave.rpc.RpcServerRequest;
import brave.rpc.RpcServerResponse;
import brave.rpc.RpcTracing;

/**
 * Decorates an {@link RpcService} to trace inbound {@link RpcRequest}s using
 * <a href="https://github.com/openzipkin/brave">Brave</a>.
 */
public final class BraveRpcService extends AbstractBraveService<RpcServerRequest, RpcRequest, RpcResponse>
        implements RpcService {

    private static final RpcRequestParser defaultRequestParser = (request, context, span) -> {
        RpcRequestParser.DEFAULT.parse(request, context, span);
        ArmeriaRpcServerParser.requestParser().parse(request, context, span);
    };

    private static final RpcResponseParser defaultResponseParser = (response, context, span) -> {
        RpcResponseParser.DEFAULT.parse(response, context, span);
        ArmeriaRpcServerParser.responseParser().parse(response, context, span);
    };

    /**
     * Creates a new tracing {@link RpcService} decorator using the specified {@link Tracing} instance.
     */
    public static Function<? super RpcService, BraveRpcService>
    newDecorator(Tracing tracing) {
        return newDecorator(RpcTracing.newBuilder(tracing)
                                      .serverRequestParser(defaultRequestParser)
                                      .serverResponseParser(defaultResponseParser)
                                      .build());
    }

    /**
     * Creates a new tracing {@link RpcService} decorator using the specified {@link RpcTracing} instance.
     */
    public static Function<? super RpcService, BraveRpcService>
    newDecorator(RpcTracing rpcTracing) {
        ensureScopeUsesRequestContext(rpcTracing.tracing());
        return service -> new BraveRpcService(service, rpcTracing);
    }

    private final Tracer tracer;
    private final RpcServerHandler handler;
    private final RequestContextCurrentTraceContext currentTraceContext;
    private final RpcService delegate;

    private BraveRpcService(RpcService delegate, RpcTracing rpcTracing) {
        super(delegate);
        this.delegate = delegate;
        final Tracing tracing = rpcTracing.tracing();
        tracer = tracing.tracer();
        handler = RpcServerHandler.create(rpcTracing);
        currentTraceContext = (RequestContextCurrentTraceContext) tracing.currentTraceContext();
    }

    @Override
    public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
        if (!ctx.config().transientServiceOptions().contains(TransientServiceOption.WITH_TRACING)) {
            return unwrap().serve(ctx, req);
        }
        final RpcServerRequest braveReq = ServiceRequestContextAdapter.asRpcServerRequest(ctx);
        final Span span = handler.handleReceive(braveReq);
        return serve0(ctx, req, braveReq, span);
    }

    @Override
    Tracer tracer() {
        return tracer;
    }

    @Override
    RequestContextCurrentTraceContext currentTraceContext() {
        return currentTraceContext;
    }

    @Override
    void maybeAddTagsToSpan(ServiceRequestContext ctx, RpcServerRequest braveReq, Span span) {
        if (span.isNoop()) {
            // For no-op spans, nothing special to do.
            return;
        }

        ctx.log().whenComplete().thenAccept(log -> {
            annotateWireSpan(log, span);
            final RpcServerResponse braveRes =
                    ServiceRequestContextAdapter.asRpcServerResponse(ctx, log, braveReq);
            handler.handleSend(braveRes, span);
        });
    }
}
