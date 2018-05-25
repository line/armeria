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

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * Decorates a {@link Service} to inject {@link HttpHeaders} when using RPC which uses HTTP as underlying
 * protocol. The {@link HttpHeaders} returned from
 * {@link #httpHeaders(ServiceRequestContext, RpcRequest, RpcResponse)} will be added to the underlying
 * {@link HttpResponse}.
 * Here is an example which uses {@code ThriftCallService} and {@code THttpService} in armeria-thrift module:
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
 * succeeded or not, use your own decorator whose {@link Request} and {@link Response} types are
 * {@link HttpRequest} and {@link HttpResponse} respectively. For example:
 *
 * <pre>{@code
 * > public final class MyOwnInjector extends SimpleDecoratingService<HttpRequest, HttpResponse> {
 * >
 * >     ...
 * >
 * >     @Override
 * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
 * >         final HttpHeaders myHeaders = ... // Create headers according to ctx, req or res.
 * >
 * >         // myHeaders will be injected to an HttpResponse.
 * >         ctx.additionalResponseHeaders().add(myHeaders);
 * >         ...
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
        final RpcResponse res = delegate().serve(ctx, req);
        return RpcResponse.from(res.whenComplete((value, cause) -> {
            final HttpHeaders headers = httpHeaders(ctx, req, res);
            if (headers != null && !headers.isEmpty()) {
                ctx.additionalResponseHeaders().add(headers);
            }
        }));
    }

    /**
     * Returns {@link HttpHeaders} which will be added to the underlying {@link HttpResponse} of the
     * {@link RpcResponse}.
     */
    @Nullable
    protected abstract HttpHeaders httpHeaders(ServiceRequestContext ctx, RpcRequest req, RpcResponse res);
}
