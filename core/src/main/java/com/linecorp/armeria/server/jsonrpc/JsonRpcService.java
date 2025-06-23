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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.streaming.ServerSentEvents;

import reactor.core.publisher.Flux;

/**
 * A JSON-RPC {@link HttpService}.
 */
@UnstableApi
public final class JsonRpcService implements HttpService {
    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final boolean shouldUseSse;
    private final Map<String, JsonRpcHandler> methodHandlers;

    /**
    * Returns a new {@link JsonRpcServiceBuilder}.
    */
    public static JsonRpcServiceBuilder builder() {
        return new JsonRpcServiceBuilder();
    }

    JsonRpcService(boolean shouldUseSse, Map<String, JsonRpcHandler> methodHandlers) {
        this.shouldUseSse = shouldUseSse;
        this.methodHandlers = requireNonNull(methodHandlers, "methodHandlers");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(
            req.aggregate()
               .thenApply(JsonRpcService::parseRequestContentAsJson)
               .thenApply(json -> dispatchRequest(ctx, json))
               .exceptionally(e -> {
                    if (e.getCause() instanceof JsonProcessingException) {
                        return HttpResponse.ofJson(HttpStatus.BAD_REQUEST, "Invalid JSON format");
                    }
                    return HttpResponse.ofFailure(e);
                })
            );
    }

    private static JsonNode parseRequestContentAsJson(AggregatedHttpRequest request) {
        try {
            return mapper.readTree(request.contentUtf8());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private HttpResponse dispatchRequest(ServiceRequestContext ctx, JsonNode rawRequest) {
        if (rawRequest.isObject()) {
            return handleUnaryRequest(ctx, rawRequest);
        } else if (rawRequest.isArray()) {
            return handleBatchRequests(ctx, rawRequest);
        } else {
            // If the request is neither an object nor an array, return an error response.
            return HttpResponse.ofJson(HttpStatus.BAD_REQUEST, "Invalid JSON-RPC request format.");
        }
    }

    private HttpResponse handleUnaryRequest(ServiceRequestContext ctx, JsonNode unary) {
        return HttpResponse.of(
            executeRpcCall(ctx, unary)
                .thenApply(HttpResponse::ofJson));
    }

    private HttpResponse handleBatchRequests(ServiceRequestContext ctx, JsonNode batch) {
        final List<CompletableFuture<DefaultJsonRpcResponse>> requests =
            StreamSupport.stream(batch.spliterator(), false)
                         .map(item -> executeRpcCall(ctx, item))
                         .toList();

        if (shouldUseSse) {
            return ServerSentEvents.fromPublisher(
                Flux.create(sink -> {
                    requests.forEach(item -> item.thenApply(JsonRpcService::toServerSentEvent)
                                                 .thenAccept(sse -> {
                                                        if (sse != null) {
                                                            sink.next(sse);
                                                        }
                                                    }));

                    CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                                     .thenAccept(a -> sink.complete());
                }));
        } else {
            return HttpResponse.of(
                CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                                 .thenApply(v -> requests.stream()
                                                         .map(req -> req.join())
                                                         .filter(x -> x != null)
                                                         .toList())
                                 .thenApply(HttpResponse::ofJson));
        }
    }

    private CompletableFuture<DefaultJsonRpcResponse> executeRpcCall(ServiceRequestContext ctx, JsonNode node) {
        return UnmodifiableFuture.completedFuture(node)
                .thenApply(JsonRpcService::parseNodeAsRpcRequest)
                .thenApply(req -> invokeMethod(ctx, req))
                .exceptionally(e -> {
                    if (e instanceof IllegalArgumentException) {
                        return new DefaultJsonRpcResponse(null, JsonRpcError.PARSE_ERROR);
                    }
                    return new DefaultJsonRpcResponse(null, JsonRpcError.INTERNAL_ERROR.withData(e));
                });
    }

    private static JsonRpcRequest parseNodeAsRpcRequest(JsonNode node) {
        try {
            return JsonRpcRequest.of(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private DefaultJsonRpcResponse invokeMethod(ServiceRequestContext ctx, JsonRpcRequest req) {
        final JsonRpcHandler handler = methodHandlers.get(req.method());
        if (handler == null) {
            return new DefaultJsonRpcResponse(req.id(), JsonRpcError.METHOD_NOT_FOUND);
        }
        return handler.handle(ctx, req)
                .thenApply(res -> buildFinalResponse(req, res))
                .join();
    }

    private DefaultJsonRpcResponse buildFinalResponse(JsonRpcRequest request, JsonRpcResponse response) {
        if (response instanceof DefaultJsonRpcResponse) {
            return (DefaultJsonRpcResponse) response;
        }

        if (response.result() != null && response.error() == null) {
            return new DefaultJsonRpcResponse(request.id(), response.result());
        }
        if (response.error() != null && response.result() == null) {
            return new DefaultJsonRpcResponse(request.id(), response.error());
        }
        return null;
    }

    private static ServerSentEvent toServerSentEvent(DefaultJsonRpcResponse response) {
        try {
            return ServerSentEvent.ofData(mapper.writeValueAsString(response));
        } catch (Exception e) {
            return null;
        }
    }
}
