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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcMessage;
import com.linecorp.armeria.common.jsonrpc.JsonRpcMethodInvokable;
import com.linecorp.armeria.common.jsonrpc.JsonRpcNotification;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.jsonrpc.JsonRpcStreamableResponse;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.common.sse.ServerSentEventBuilder;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.common.jsonrpc.JsonRpcSseMessage;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.streaming.ServerSentEvents;

import io.netty.util.AttributeKey;

/**
 * A JSON-RPC {@link HttpService}.
 *
 * <p>Example:
 * <pre>{@code
 * class EchoHandler implements JsonRpcMethodHandler {
 *  @Override
 *  public CompletableFuture<JsonRpcResponse> onRequest(ServiceRequestContext ctx, JsonRpcRequest request) {
 *      return UnmodifiableFuture.completedFuture(JsonRpcResponse.of(request.params()));
 *  }
 * }
 *
 * JsonRpcService jsonRpcService = JsonRpcService.builder()
 *                                               .methodHandler("echo", new EchoHandler())
 *                                               .build();
 *
 * ServerBuilder sb = Server.builder();
 * sb.service("/json-rpc", jsonRpcService);
 * }</pre>
 */
@UnstableApi
public final class JsonRpcService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcService.class);

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
    private static final AttributeKey<JsonRpcMessage> REQUEST_KEY =
            AttributeKey.valueOf(JsonRpcService.class, "REQUEST_KEY");

    /**
     * Returns a new {@link JsonRpcServiceBuilder}.
     */
    public static JsonRpcServiceBuilder builder() {
        return new JsonRpcServiceBuilder();
    }

    private final Map<String, JsonRpcMethodHandler> methodHandlers;
    private final JsonRpcHandler defaultHandler;
    @Nullable
    private final HttpService fallbackService;
    private final JsonRpcExceptionHandler exceptionHandler;
    private final JsonRpcStatusFunction statusFunction;
    private final boolean enableServerSentEvents;

    JsonRpcService(Map<String, JsonRpcMethodHandler> methodHandlers, JsonRpcHandler defaultHandler,
                   @Nullable HttpService fallbackService, JsonRpcExceptionHandler exceptionHandler,
                   JsonRpcStatusFunction statusFunction,
                   boolean enableServerSentEvents) {
        this.methodHandlers = methodHandlers;
        this.defaultHandler = defaultHandler;
        this.fallbackService = fallbackService;
        this.exceptionHandler = exceptionHandler;
        this.statusFunction = statusFunction;
        this.enableServerSentEvents = enableServerSentEvents;
    }

    // TODO(ikhoon): Override ServiceOptions method to disable request timeout and max request length for SSE.

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (req.method() != HttpMethod.POST) {
            if (fallbackService != null) {
                return fallbackService.serve(ctx, req);
            } else {
                return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
            }
        }
        final MediaType contentType = req.contentType();
        if (contentType == null || !contentType.isJson()) {
            if (fallbackService != null) {
                return fallbackService.serve(ctx, req);
            } else {
                return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
        }

        if (enableServerSentEvents && !canAcceptSse(req.headers())) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                    "Both 'text/event-stream' and 'application/json' must be present in " +
                            "the Accept header.");
        }

        return HttpResponse.of(req.aggregate().thenCompose(aggReq -> {
            try {
                final JsonNode json;
                try {
                    json = mapper.readTree(aggReq.content().array());
                } catch (IOException e) {
                    throw new JsonRpcParseException(e);
                }
                return dispatchRequest(ctx, json).handle((rpcResponse, cause) -> {
                    final JsonRpcMessage message = ctx.attr(REQUEST_KEY);
                    if (cause != null) {
                        rpcResponse = handleException(ctx, cause, message);
                    }
                    return toHttpResponse(ctx, message, rpcResponse);
                });
            } catch (Exception ex) {
                final JsonRpcMessage message = ctx.attr(REQUEST_KEY);
                final JsonRpcResponse recovered = handleException(ctx, ex, message);
                return UnmodifiableFuture.completedFuture(toHttpResponse(ctx, message, recovered));
            }
        }));
    }

    private JsonRpcResponse handleException(ServiceRequestContext ctx, Throwable cause,
                                            @Nullable JsonRpcMessage message) {
        cause = Exceptions.peel(cause);
        JsonRpcResponse recovered = exceptionHandler.handleException(ctx, message, cause);
        assert recovered != null;
        if (message instanceof JsonRpcRequest && recovered.id() == null) {
            recovered = recovered.withId(((JsonRpcRequest) message).id());
        }
        return recovered;
    }

    private HttpResponse toHttpResponse(ServiceRequestContext ctx, @Nullable JsonRpcMessage request,
                                        @Nullable JsonRpcResponse response) {
        if (response == null) {
            return HttpResponse.of(ResponseHeaders.of(HttpStatus.ACCEPTED));
        }

        if (response instanceof JsonRpcStreamableResponse) {
            checkState(enableServerSentEvents,
                    "The JsonRpcStreamableResponse is not supported unless server-sent events are enabled.");
            return toSseHttpResponse(ctx, request, (JsonRpcStreamableResponse) response);
        } else {
            return toUnaryHttpResponse(ctx, request, response);
        }
    }

    private HttpResponse toUnaryHttpResponse(ServiceRequestContext ctx, @Nullable JsonRpcMessage request,
                                             JsonRpcResponse response) {

        if (response.id() == null && request instanceof JsonRpcRequest) {
            final JsonRpcRequest req = (JsonRpcRequest) request;
            response = response.withId(req.id());
        }

        ctx.logBuilder().responseContent(response, response);

        if (response.isSuccess()) {
            return HttpResponse.ofJson(response);
        }

        final HttpStatus status;
        if (request == null) {
            status = HttpStatus.BAD_REQUEST;
        } else {
            final JsonRpcRequest rpcRequest = (JsonRpcRequest) request;
            final JsonRpcError error = response.error();
            assert error != null;
            status = statusFunction.toHttpStatus(ctx, rpcRequest, response, error);
            assert status != null;
        }
        return HttpResponse.ofJson(status, response);
    }

    private static HttpResponse toSseHttpResponse(ServiceRequestContext ctx, @Nullable JsonRpcMessage request,
                                                  JsonRpcStreamableResponse stream) {
        final StreamMessage<ServerSentEvent> events = stream.map(message -> {
            String messageId = null;
            String eventType = null;
            if (message instanceof JsonRpcSseMessage) {
                messageId = ((JsonRpcSseMessage) message).messageId();
                eventType = ((JsonRpcSseMessage) message).eventType();
                message = ((JsonRpcSseMessage) message).unwrap();
            }

            if (message instanceof JsonRpcResponse) {
                final JsonRpcResponse response = (JsonRpcResponse) message;
                if (response.id() == null && request instanceof JsonRpcRequest) {
                    final JsonRpcRequest req = (JsonRpcRequest) request;
                    message = response.withId(req.id());
                }
                ctx.logBuilder().responseContent(message, message);
            }

            final ServerSentEventBuilder sseBuilder = ServerSentEvent.builder();
            if (messageId != null) {
                sseBuilder.id(messageId);
            }
            if (eventType != null) {
                sseBuilder.event(eventType);
            }
            try {
                sseBuilder.data(mapper.writeValueAsString(message));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "Failed to serialize a JSON-RPC message to JSON string: " + message, e);
            }
            return sseBuilder.build();
        });
        return ServerSentEvents.fromPublisher(events);
    }

    @VisibleForTesting
    CompletableFuture<@Nullable JsonRpcResponse> dispatchRequest(ServiceRequestContext ctx, JsonNode jsonNode) {
        if (jsonNode.isObject()) {
            return handleUnaryRequest(ctx, jsonNode);
        } else {
            // TODO(ikhoon): Implement batch request handling.
            return UnmodifiableFuture.completedFuture(JsonRpcResponse.ofFailure(
                    JsonRpcError.INVALID_REQUEST.withData("Batch requests are not supported by this server.")));
        }
    }

    private CompletableFuture<@Nullable JsonRpcResponse> handleUnaryRequest(ServiceRequestContext ctx,
                                                                            JsonNode unary) {
        final JsonRpcMessage message = parseNodeAsRpcMessage(unary);
        ctx.setAttr(REQUEST_KEY, message);
        ctx.logBuilder().requestContent(message, unary);

        final CompletableFuture<?> future = invokeMethod(ctx, message);
        requireNonNull(future, "A JSON-RPC handler returned null");
        return future.thenApply(res -> {
            if (res == null) {
                return null;
            }

            if (message instanceof JsonRpcNotification || message instanceof JsonRpcResponse) {
                // Notifications and Responses do not expect any response.
                logger.warn("A response was returned for a notification or a response. request={}, response={}",
                        message, res);
                return null;
            }

            if (!(res instanceof JsonRpcResponse)) {
                throw new IllegalStateException(
                        "A response Handler returned a non-JSON-RPC response: " + res);
            }
            return (JsonRpcResponse) res;
        });
    }

    private static JsonRpcMessage parseNodeAsRpcMessage(JsonNode node) {
        if (node.has("method") && node.has("id")) {
            return JsonRpcRequest.fromJson(node);
        }
        if (node.has("method") && !node.has("id")) {
            return JsonRpcNotification.fromJson(node);
        }
        if (node.has("result") || node.has("error")) {
            return JsonRpcResponse.fromJson(node);
        }

        throw new JsonRpcParseException("Cannot deserialize JsonRpcMessage: " + node);
    }

    private CompletableFuture<?> invokeMethod(ServiceRequestContext ctx, JsonRpcMessage message) {
        if (message instanceof JsonRpcResponse) {
            return defaultHandler.handleRpcCall(ctx, message);
        }

        final JsonRpcMethodHandler handler = methodHandlers.get(((JsonRpcMethodInvokable) message).method());
        if (handler == null) {
            return defaultHandler.handleRpcCall(ctx, message);
        }
        if (message instanceof JsonRpcNotification) {
            final JsonRpcNotification noti = (JsonRpcNotification) message;
            return handler.onNotification(ctx, noti);
        }

        final JsonRpcRequest req = (JsonRpcRequest) message;
        return handler.onRequest(ctx, req);
    }

    private static boolean canAcceptSse(RequestHeaders headers) {
        final List<MediaType> acceptTypes = headers.accept();
        if (acceptTypes.isEmpty()) {
            return false;
        }
        boolean jsonMatched = false;
        boolean sseMatched = false;
        for (MediaType acceptType : acceptTypes) {
            if (acceptType.isJson()) {
                jsonMatched = true;
                if (sseMatched) {
                    return true;
                }
            }
            if (acceptType.is(MediaType.EVENT_STREAM)) {
                sseMatched = true;
                if (jsonMatched) {
                    return true;
                }
            }
        }
        return false;
    }
}
