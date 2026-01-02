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

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcMessage;
import com.linecorp.armeria.common.jsonrpc.JsonRpcNotification;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A handler that handles all incoming {@link JsonRpcRequest}, {@link JsonRpcNotification} and
 * {@link JsonRpcResponse}.
 * If both a method-specific {@link JsonRpcMethodHandler} and a {@link JsonRpcHandler} are set,
 * the method-specific handler takes precedence.
 */
@UnstableApi
@FunctionalInterface
public interface JsonRpcHandler {

    /**
     * Returns a fallback {@link JsonRpcHandler} that handles unregistered methods by returning a
     * {@link JsonRpcError#METHOD_NOT_FOUND} error response for {@link JsonRpcRequest}s,
     * and ignoring {@link JsonRpcNotification}s and {@link JsonRpcResponse}s.
     */
    static JsonRpcHandler ofFallback() {
        return (ctx, message) -> {
            if (message instanceof JsonRpcNotification || message instanceof JsonRpcResponse) {
                // Just ignore notifications and responses.
                return UnmodifiableFuture.completedFuture(null);
            }

            final JsonRpcRequest request = (JsonRpcRequest) message;
            return UnmodifiableFuture.completedFuture(
                    JsonRpcResponse.ofFailure(request.id(), JsonRpcError.METHOD_NOT_FOUND));
        };
    }

    /**
     * Handles incoming {@link JsonRpcRequest} or {@link JsonRpcNotification} whose {@code method} is not
     * registered with the {@link JsonRpcService}, and also processes {@link JsonRpcResponse} messages that
     * may be received as an input.
     *
     * <p>Note that when the input is a {@link JsonRpcNotification} or {@link JsonRpcResponse},
     * the returned {@link CompletableFuture} must be completed with null, as any returned value will be
     * ignored otherwise.
     */
    CompletableFuture<@Nullable JsonRpcResponse> handleRpcCall(ServiceRequestContext ctx,
                                                               JsonRpcMessage message);
}
