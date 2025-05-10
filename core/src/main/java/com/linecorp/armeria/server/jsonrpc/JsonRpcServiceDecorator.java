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
package com.linecorp.armeria.server.jsonrpc;

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.jsonrpc.JsonRpcUtil;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * An {@link HttpService} decorator that handles JSON-RPC responses.
 * If the request is a JSON-RPC notification, it returns an HTTP 200 OK response.
 * Otherwise, it converts the delegate's response into a JSON-RPC response format.
 */
public final class JsonRpcServiceDecorator extends SimpleDecoratingHttpService {

    final HttpService delegate;

    final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     *
     * @param delegate the {@link HttpService} to decorate
     */
    public JsonRpcServiceDecorator(HttpService delegate) {
        super(delegate);
        this.delegate = delegate;
    }

    /**
     * {@inheritDoc}
     *
     * @param ctx the {@link ServiceRequestContext} of this service
     * @param req the {@link HttpRequest} to serve
     * @return the {@link HttpResponse}
     * @throws Exception if an error occurs during request processing
     */
    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {

        final HttpResponse res = delegate.serve(ctx, req);

        final CompletableFuture<HttpResponse> future = res.aggregate()
                                                          .thenApply(aggregated ->
                                                                             toRpcResponse(aggregated, ctx));
        return HttpResponse.of(future);
    }

    /**
     * Converts an {@link AggregatedHttpResponse} to a JSON-RPC response.
     *
     * @param res the {@link AggregatedHttpResponse} to convert
     * @param ctx the {@link ServiceRequestContext}
     * @return an {@link HttpResponse} in JSON-RPC format,
     *         or the original response if it's not a JSON-RPC request.
     */
    private HttpResponse toRpcResponse(AggregatedHttpResponse res, ServiceRequestContext ctx) {
        final Object id = ctx.attr(JsonRpcAttributes.ID);
        final Boolean isNotification = ctx.attr(JsonRpcAttributes.IS_NOTIFICATION);
        final String method = ctx.attr(JsonRpcAttributes.METHOD);

        if (id == null || isNotification == null || method == null) {
            // Non-JsonRPC requests
            return res.toHttpResponse();
        }

        if (isNotification) {
            // Notifications are not confirmable by definition,
            // since they do not have a Response object to be returned.
            // As such, the Client would not be aware of any errors
            // (like e.g. "Invalid params","Internal error").
            return HttpResponse.of(HttpStatus.OK);
        }

        return HttpResponse.ofJson(MediaType.JSON_UTF_8,
                                   JsonRpcUtil.parseDelegateResponse(res, id, method, mapper));
    }
}
