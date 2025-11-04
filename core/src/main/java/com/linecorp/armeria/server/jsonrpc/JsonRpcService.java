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

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A JSON-RPC {@link HttpService}.
 *
 * <p>Example:
 * <pre>{@code
 * class EchoHandler implements JsonRpcHandler {
 *  @Override
 *  public CompletableFuture<JsonRpcResponse> handle(ServiceRequestContext ctx, JsonRpcRequest request) {
 *      return UnmodifiableFuture.completedFuture(JsonRpcResponse.of(request.params()));
 *  }
 * }
 *
 * JsonRpcService jsonRpcService = JsonRpcService.builder()
 *                                               .addHandler("echo", new EchoHandler())
 *                                               .build();
 *
 * ServerBuilder sb = Server.builder();
 * sb.service("/json-rpc", jsonRpcService);
 * }</pre>
 */
@UnstableApi
public final class JsonRpcService implements HttpService {
    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final Map<String, JsonRpcHandler> methodHandlers;

    /**
    * Returns a new {@link JsonRpcServiceBuilder}.
    */
    public static JsonRpcServiceBuilder builder() {
        return new JsonRpcServiceBuilder();
    }

    JsonRpcService(Map<String, JsonRpcHandler> methodHandlers) {
        this.methodHandlers = requireNonNull(methodHandlers, "methodHandlers");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }

        final MediaType contentType = req.contentType();
        if (contentType == null || !contentType.isJson()) {
            return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }

        return HttpResponse.of(
                req.aggregate()
                   .handle((aggregate, throwable) -> {
                       if (throwable != null) {
                           return HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR,
                                                      JsonRpcError.INTERNAL_ERROR.withData(
                                                              throwable.getMessage()));
                       }

                       try {
                           final JsonNode parsedJson = parseRequestContentAsJson(aggregate);
                           return dispatchRequest(ctx, parsedJson);
                       } catch (Exception e) {
                           if (e instanceof IllegalArgumentException) {
                               return HttpResponse.ofJson(HttpStatus.BAD_REQUEST,
                                                          JsonRpcError.PARSE_ERROR);
                           }
                           return HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR,
                                                      JsonRpcError.INTERNAL_ERROR.withData(e.getMessage()));
                       }
                   }));
    }

    @VisibleForTesting
    static JsonNode parseRequestContentAsJson(AggregatedHttpRequest request) {
        try {
            return mapper.readTree(request.contentUtf8());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @VisibleForTesting
    HttpResponse dispatchRequest(ServiceRequestContext ctx, JsonNode rawRequest) {
        if (rawRequest.isObject()) {
            return handleUnaryRequest(ctx, rawRequest);
        } else {
            return HttpResponse.ofJson(HttpStatus.BAD_REQUEST,
                    JsonRpcError.INVALID_REQUEST.withData("Batch requests are not supported by this server."));
        }
    }

    private HttpResponse handleUnaryRequest(ServiceRequestContext ctx, JsonNode unary) {
        return HttpResponse.of(executeRpcCall(ctx, unary).thenApply(JsonRpcService::toHttpResponse));
    }

    private static HttpResponse toHttpResponse(DefaultJsonRpcResponse response) {
        if (response == null) {
            return HttpResponse.of(ResponseHeaders.of(HttpStatus.ACCEPTED));
        }

        if (response.error() != null) {
            if (response.error().code() == JsonRpcError.INTERNAL_ERROR.code()) {
                return HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR, response);
            }

            return HttpResponse.ofJson(HttpStatus.BAD_REQUEST, response);
        }

        return HttpResponse.ofJson(response);
    }

    @VisibleForTesting
    CompletableFuture<DefaultJsonRpcResponse> executeRpcCall(ServiceRequestContext ctx, JsonNode node) {
        final JsonRpcRequest request;
        try {
            request = parseNodeAsRpcRequest(node);
            maybeLogRequestContent(ctx, request, node);
        } catch (IllegalArgumentException e) {
            return UnmodifiableFuture.completedFuture(
                    new DefaultJsonRpcResponse(null, JsonRpcError.PARSE_ERROR.withData(e.getMessage())));
        }

        return invokeMethod(ctx, request)
                .exceptionally(e -> {
                    return new DefaultJsonRpcResponse(request.id(),
                                                      JsonRpcError.INTERNAL_ERROR.withData(e.getMessage()));
                });
    }

    private static void maybeLogRequestContent(ServiceRequestContext ctx, JsonRpcRequest request,
                                               JsonNode node) {
        // Introduce another flag or add a property to the builder instead of using
        // the annotatedServiceContentLogging flag.
        if (!Flags.annotatedServiceContentLogging()) {
            return;
        }

        ctx.logBuilder().requestContent(request, node);
    }

    @VisibleForTesting
    static JsonRpcRequest parseNodeAsRpcRequest(JsonNode node) {
        try {
            return JsonRpcRequest.of(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @VisibleForTesting
    CompletableFuture<DefaultJsonRpcResponse> invokeMethod(ServiceRequestContext ctx, JsonRpcRequest req) {
        final JsonRpcHandler handler = methodHandlers.get(req.method());

        // Notification
        if (req.id() == null) {
            if (handler != null) {
                return handler.handle(ctx, req)
                              .thenApply(res -> null);
            }
            return UnmodifiableFuture.completedFuture(null);
        }

        if (handler == null) {
            return UnmodifiableFuture.completedFuture(
                    new DefaultJsonRpcResponse(req.id(), JsonRpcError.METHOD_NOT_FOUND));
        }
        return handler.handle(ctx, req)
                      .thenApply(res -> {
                          final DefaultJsonRpcResponse finalResponse = buildFinalResponse(req, res);
                          maybeLogResponseContent(ctx, finalResponse, res);
                          return finalResponse;
                      });
    }

    private static void maybeLogResponseContent(ServiceRequestContext ctx,
                                                DefaultJsonRpcResponse response,
                                                JsonRpcResponse originalResponse) {
        if (!Flags.annotatedServiceContentLogging()) {
            return;
        }

        ctx.logBuilder().responseContent(response, originalResponse);
    }

    @VisibleForTesting
    DefaultJsonRpcResponse buildFinalResponse(JsonRpcRequest request, JsonRpcResponse response) {
        if (response instanceof DefaultJsonRpcResponse) {
            return (DefaultJsonRpcResponse) response;
        }

        final Object id = request.id();
        final Object result = response.result();
        final JsonRpcError error = response.error();
        if (id != null && result != null && error == null) {
            return new DefaultJsonRpcResponse(id, result);
        }
        if (error != null && result == null) {
            return new DefaultJsonRpcResponse(id, error);
        }
        return new DefaultJsonRpcResponse(
                id,
                // Leave a warning message instead of sending this error response to the client
                // because the server-side handler implementation is faulty.
                JsonRpcError.INTERNAL_ERROR.withData(
                        "A response cannot have both or neither 'result' and 'error' fields."));
    }
}
