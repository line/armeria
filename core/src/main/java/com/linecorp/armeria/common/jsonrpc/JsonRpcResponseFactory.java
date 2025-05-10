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
package com.linecorp.armeria.common.jsonrpc;

import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A factory class for creating JSON-RPC 2.0 {@link JsonRpcResponse} objects and converting them
 * into Armeria {@link HttpResponse} objects.
 */
public final class JsonRpcResponseFactory {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcResponseFactory.class);

    /**
     * Creates a JSON-RPC success response from a result {@link JsonNode}.
     */
    public static JsonRpcResponse ofSuccess(JsonNode result, @Nullable Object id) {
        return JsonRpcResponse.ofSuccess(result, id);
    }

    /**
     * Creates a JSON-RPC error response from a {@link JsonRpcError}.
     */
    public static JsonRpcResponse ofError(JsonRpcError error, @Nullable Object id) {
        return JsonRpcResponse.ofError(error, id);
    }

    /**
     * Creates a final {@link HttpResponse} containing the serialized JSON-RPC response.
     * Returns HTTP {@link HttpStatus#OK}, with the JSON-RPC error details included in the body
     * if the {@code rpcResponse} represents an error.
     *
     * @param rpcResponse The {@link JsonRpcResponse} to serialize (can be success or error).
     * @param mapper The {@link ObjectMapper} used for serialization.
     * @param requestId The original request ID (used for logging in case of serialization error).
     * @return An {@link HttpResponse} with status OK and JSON content type, containing the serialized response.
     *         Returns an {@link HttpStatus#INTERNAL_SERVER_ERROR} response if serialization fails.
     */
    public static HttpResponse toHttpResponse(JsonRpcResponse rpcResponse, ObjectMapper mapper,
                                                                  @Nullable Object requestId) {
        try {
            final String responseBody = mapper.writeValueAsString(rpcResponse);
            return AggregatedHttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, responseBody)
                                         .toHttpResponse();

        } catch (JsonProcessingException jsonProcessingException) {
            logger.error("CRITICAL: Failed to serialize final JSON-RPC response for request id {}: {}",
                         requestId, jsonProcessingException.getMessage(), jsonProcessingException);

            return AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                                   "Internal Server Error: Failed to serialize JSON-RPC response.")
                                         .toHttpResponse();
        }
    }

    /**
     * Creates a {@link JsonRpcResponse} representing an error based on a {@link Throwable} caught
     * during request processing or delegate execution.
     *
     * @param throwable The exception caught.
     * @param id The ID from the original JSON-RPC request.
     * @param methodName The name of the JSON-RPC method being processed.
     * @return A {@link JsonRpcResponse} containing the mapped {@link JsonRpcError}.
     */
    public static JsonRpcResponse fromThrowable(Throwable throwable, @Nullable Object id, String methodName) {
        final JsonRpcError error = mapThrowableToJsonRpcError(throwable, methodName, id);
        return JsonRpcResponse.ofError(error, id);
    }

    /**
     * Maps a {@link Throwable} to a specific {@link JsonRpcError}.
     * Unwraps {@link CompletionException}.
     */
    private static JsonRpcError mapThrowableToJsonRpcError(Throwable exception, String methodName,
                                                           @Nullable Object requestId) {
        Throwable cause = exception;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }

        logger.warn("Mapping throwable for method '{}' (id: {}): {}: {}",
                    methodName, requestId, cause.getClass().getName(), cause.getMessage(), cause);

        if (cause instanceof JsonProcessingException) {
            return JsonRpcError.internalError(
                    "Internal Error: Failed processing delegate response. Cause: " + cause.getMessage());
        } else if (cause instanceof IllegalArgumentException) {
            return JsonRpcError.invalidRequest(
                    "Invalid argument or state encountered during processing: " + cause.getMessage());
        } else {
            return JsonRpcError.internalError(
                    "Internal server error during processing for method '" + methodName + "': " +
                    cause.getClass().getSimpleName() + " - " + cause.getMessage());
        }
    }

    private JsonRpcResponseFactory() {}
}
