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

import com.google.common.collect.ImmutableMap;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.HttpService;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Constructs a {@link JsonRpcService} to serve JSON-RPC services.
 */
@UnstableApi
public final class JsonRpcServiceBuilder {

    private final ImmutableMap.Builder<String, JsonRpcMethodHandler> methodHandlers = ImmutableMap.builder();
    private JsonRpcHandler defaultHandler = JsonRpcHandler.ofFallback();
    @Nullable
    private HttpService fallbackService;
    @Nullable
    private JsonRpcExceptionHandler exceptionHandler;
    @Nullable
    private JsonRpcStatusFunction statusFunction;
    private boolean enableServerSentEvents;

    JsonRpcServiceBuilder() {}

    /**
     * Adds a {@link JsonRpcMethodHandler} for the specified method name.
     */
    public JsonRpcServiceBuilder methodHandler(String methodName, JsonRpcMethodHandler handler) {
        requireNonNull(methodName, "methodName");
        checkArgument(!methodName.isEmpty(), "methodName must not be empty");
        requireNonNull(handler, "handler");

        methodHandlers.put(methodName, handler);
        return this;
    }

    /**
     * Sets the {@link JsonRpcHandler} that handles requests whose methods are not registered by
     * {@link JsonRpcMethodHandler}, or incoming responses.
     * If both a method-specific {@link JsonRpcMethodHandler} and a {@link JsonRpcHandler} are set,
     * the method-specific handler takes precedence.
     *
     * <p>You can also use {@link JsonRpcHandler} as a global handler to process all requests when no
     * method-specific handler is registered. For example:
     * <pre>{@code
     * class JsonRpcGlobalHandler implements JsonRpcHandler {
     *    @Override
     *    public CompletableFuture<JsonRpcResponse> handle(ServiceRequestContext ctx, JsonRpcMessage message) {
     *      if (message instanceof JsonRpcRequest) {
     *        JsonRpcRequest request = (JsonRpcRequest) message;
     *        MyHandler handler = handlers.get(request.method());
     *        ... // Custom handling logic
     *      } else {
     *         return CompletableFuture.completedFuture(null); // Ignore notifications and responses
     *      }
     *    }
     * }
     *
     * JsonRpcService.builder()
     *               .handler(new JsonRpcGlobalHandler())
     *               .build();
     * }</pre>
     */
    public JsonRpcServiceBuilder handler(JsonRpcHandler defaultHandler) {
        requireNonNull(defaultHandler, "defaultHandler");
        this.defaultHandler = defaultHandler;
        return this;
    }

    /**
     * Sets the fallback {@link HttpService} that handles non-JSON-RPC requests.
     * This is useful when you want to serve both JSON-RPC and other types of requests from the same endpoint.
     * The fallback service is invoked when the HTTP method is not {@code POST} or the Content-Type is not
     * {@code application/json}.
     */
    public JsonRpcServiceBuilder fallbackService(HttpService fallbackService) {
        requireNonNull(fallbackService, "fallbackService");
        this.fallbackService = fallbackService;
        return this;
    }

    /**
     * Adds the {@link JsonRpcExceptionHandler} that handles exceptions thrown during request processing.
     * If multiple handlers are added, the latter is composed with the former using
     * {@link JsonRpcExceptionHandler#orElse(JsonRpcExceptionHandler)}.
     */
    public JsonRpcServiceBuilder exceptionHandler(JsonRpcExceptionHandler exceptionHandler) {
        requireNonNull(exceptionHandler, "exceptionHandler");
        if (this.exceptionHandler == null) {
            this.exceptionHandler = exceptionHandler;
        } else {
            this.exceptionHandler = this.exceptionHandler.orElse(exceptionHandler);
        }
        return this;
    }

    public JsonRpcServiceBuilder statusFunction(JsonRpcStatusFunction statusFunction) {
        requireNonNull(statusFunction, "statusFunction");
        if (this.statusFunction == null) {
            this.statusFunction = statusFunction;
        } else {
            this.statusFunction = this.statusFunction.orElse(statusFunction);
        }
        return this;
    }

    /**
     * Enables Server-Sent Events (SSE) support for this JSON-RPC service.
     * Clients must set the {@link HttpHeaderNames#ACCEPT} to include both {@code application/json} and
     * {@code text/event-stream} to receive SSE responses.
     * Default is {@code false}.
     */
    public JsonRpcServiceBuilder enableServerSentEvents(boolean enableServerSentEvents) {
        this.enableServerSentEvents = enableServerSentEvents;
        return this;
    }

    /**
     * Constructs a new {@link JsonRpcService}.
     */
    public JsonRpcService build() {
        JsonRpcExceptionHandler exceptionHandler = this.exceptionHandler;
        if (exceptionHandler == null) {
            exceptionHandler = JsonRpcExceptionHandler.of();
        } else {
            exceptionHandler = exceptionHandler.orElse(JsonRpcExceptionHandler.of());
        }
        JsonRpcStatusFunction statusFunction = this.statusFunction;
        if (statusFunction == null) {
            statusFunction = JsonRpcStatusFunction.of();
        } else {
            statusFunction = statusFunction.orElse(JsonRpcStatusFunction.of());
        }

        return new JsonRpcService(methodHandlers.build(), defaultHandler, fallbackService,
                                  exceptionHandler, statusFunction, enableServerSentEvents);
    }
}
