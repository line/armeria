/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.DefaultRpcResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * Decorates a {@link Service} to inject {@link HttpHeaders} when using an RPC {@link Service}.
 * A Thrift call replies with an {@link RpcResponse} which is wrapped by an {@link HttpResponse}.
 * The {@link HttpHeaders} returned from {@link #httpHeaders(ServiceRequestContext, RpcRequest, RpcResponse)}
 * will be added to that {@link HttpResponse}.
 *
 * <pre>{@code
 * final ServerBuilder sb = ...
 * sb.service("/", ThriftCallService.of(thriftHandler)
 *                                  .decorate(...)
 *                                   // A subclass of HttpHeaderInjectingRpcService
 *                                  .decorate(MyInjectingService::new)
 *                                  .decorate(THttpService.newDecorator()));
 * }</pre>
 *
 * <p>Note that this class adds {@link HttpHeaders} only when the {@link HttpRequest} is decoded to
 * an {@link RpcRequest} successfully. If you want to add {@link HttpHeaders} regardless of the decoding
 * succeeded or not, use your own decorator:
 *
 * <pre>{@code
 * > public final class MyOwnInjector extends SimpleDecoratingService<HttpRequest, HttpResponse> {
 * >     MyOwnInjector(Service<HttpRequest, HttpResponse> delegate) {
 * >         super(delegate);
 * >     }
 * >
 * >     @Override
 * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
 * >         final HttpResponse originalRes = delegate().serve(ctx, req);
 * >         final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
 * >         final HttpResponse newRes = HttpResponse.from(future);
 * >
 * >         originalRes.aggregate().whenComplete((res, cause) -> {
 * >             if (cause != null) {
 * >                 future.completeExceptionally(cause);
 * >             } else {
 * >                 final HttpHeaders headers =
 * >                 res.headers().add(AsciiString.of("my-header"), "foo");
 * >                 future.complete(HttpResponse.of(headers, res.content(), res.trailingHeaders()));
 * >             }
 * >         });
 * >         return newRes;
 * >     }
 * > }
 * }</pre>
 *
 * <p>Then, decorate it:
 *
 * <pre>{@code
 * final ServerBuilder sb = ...
 * sb.service("/", ThriftCallService.of(thriftHandler)
 *                                  .decorate(...)
 *                                  .decorate(THttpService.newDecorator())
 *                                  .decorate(MyOwnInjector::new));
 * }</pre>
 */
public abstract class HttpHeaderInjectingRpcService extends SimpleDecoratingService<RpcRequest, RpcResponse> {

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    protected HttpHeaderInjectingRpcService(Service<RpcRequest, RpcResponse> delegate) {
        super(delegate);
    }

    @Override
    public RpcResponse serve(ServiceRequestContext ctx, RpcRequest req) throws Exception {
        final RpcResponse originalRes = delegate().serve(ctx, req);
        final DefaultRpcResponse newRes = new DefaultRpcResponse();

        originalRes.whenComplete((value, cause) -> {
            final HttpHeaders headers = httpHeaders(ctx, req, originalRes);
            if (headers != null && !headers.isEmpty()) {
                ctx.addAdditionalResponseHeaders(headers);
            }

            if (cause != null) {
                newRes.completeExceptionally(cause);
                return;
            }
            if (value instanceof RpcResponse) {
                ((RpcResponse) value).whenComplete((rpcResponseResult, rpcResponseCause) -> {
                    if (rpcResponseCause != null) {
                        newRes.completeExceptionally(Exceptions.peel(rpcResponseCause));
                        return;
                    }
                    newRes.complete(rpcResponseResult);
                });
            } else {
                newRes.complete(value);
            }
        });
        return newRes;
    }

    /**
     * Returns {@link HttpHeaders} which will be added to the {@link HttpResponse} which wraps the specified
     * {@link RpcResponse}.
     */
    @Nullable
    protected abstract HttpHeaders httpHeaders(ServiceRequestContext ctx, RpcRequest req, RpcResponse res);
}
