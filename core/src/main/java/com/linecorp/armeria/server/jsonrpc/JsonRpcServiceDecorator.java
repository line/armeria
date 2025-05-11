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

    /**
     * The delegate {@link HttpService} that this decorator wraps and whose responses are adapted
     * to the JSON-RPC format.
     */
    final HttpService delegate;

    /**
     * The {@link ObjectMapper} used for processing the delegate's response content,
     * particularly when converting it into a JSON-RPC result or error.
     */
    final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     * The decorated service is expected to produce responses that can be meaningfully
     * converted into the 'result' or 'error' field of a JSON-RPC response.
     *
     * @param delegate the {@link HttpService} to decorate. Must not be {@code null}.
     */
    public JsonRpcServiceDecorator(HttpService delegate) {
        super(delegate);
        this.delegate = delegate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation asynchronously serves the request using the delegate {@link HttpService}
     * and then transforms the {@link AggregatedHttpResponse} into a JSON-RPC compliant response.
     * If the incoming request was identified as a JSON-RPC notification (based on attributes set
     * in the {@link ServiceRequestContext}, likely by a preceding parser), it returns an
     * HTTP 200 OK response with no content. Otherwise, it uses the delegate's response content
     * as the 'result' or 'error' part of the JSON-RPC response, including the original request's ID.
     * If the request was not identified as a JSON-RPC request (e.g. missing required attributes),
     * the delegate's response is returned as is.
     *
     * @param ctx the {@link ServiceRequestContext} of this service, used to access
     *            {@link JsonRpcAttributes} like ID, method, and notification status.
     * @param req the {@link HttpRequest} to serve.
     * @return an {@link HttpResponse} which will be a JSON-RPC formatted response,
     *         an empty HTTP 200 OK for notifications, or the original response from the delegate
     *         if not a JSON-RPC request.
     * @throws Exception if the delegate service throws an exception.
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
     * Converts an {@link AggregatedHttpResponse} from the delegate service into an appropriate
     * JSON-RPC {@link HttpResponse}.
     *
     * <p>It inspects {@link JsonRpcAttributes} (ID, method, and notification status) from the
     * {@link ServiceRequestContext} to determine the nature of the original request:
     * <ul>
     *     <li>If essential JSON-RPC attributes are missing from the context, it's treated as a non-JSON-RPC
     *         request, and the original aggregated response is returned as is.</li>
     *     <li>If the {@code IS_NOTIFICATION} attribute is true, an HTTP 200 OK response with no content
     *         is returned, as per JSON-RPC specification for notifications.</li>
     *     <li>Otherwise (it's a non-notification JSON-RPC request), the content of the
     *         {@code res} is used to construct a JSON-RPC response object (containing 'result' or 'error')
     *         using
     *         {@link JsonRpcUtil#parseDelegateResponse(AggregatedHttpResponse, Object, String, ObjectMapper)},
     *         including the original request's ID. The response is then returned with a
     *         {@code Content-Type} of {@code application/json; charset=utf-8}.</li>
     * </ul>
     *
     * @param res the {@link AggregatedHttpResponse} from the delegate service.
     * @param ctx the {@link ServiceRequestContext}, used to retrieve {@link JsonRpcAttributes#ID},
     *            {@link JsonRpcAttributes#IS_NOTIFICATION}, and {@link JsonRpcAttributes#METHOD}.
     * @return an {@link HttpResponse} formatted for JSON-RPC, or the original response if not applicable.
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
            return HttpResponse.of(HttpStatus.OK, MediaType.ANY_TYPE, "");
        }

        return HttpResponse.ofJson(MediaType.JSON_UTF_8,
                                   JsonRpcUtil.parseDelegateResponse(res, id, method, mapper));
    }
}
