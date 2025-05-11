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
 * A utility factory class providing static methods for creating JSON-RPC 2.0 {@link JsonRpcResponse} objects
 * and for converting these responses into Armeria {@link HttpResponse} objects.
 * This class is not meant to be instantiated.
 */
public final class JsonRpcResponseFactory {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcResponseFactory.class);

    /**
     * Creates a new successful JSON-RPC response with the given result and ID.
     * This is a convenience method that delegates to {@link JsonRpcResponse#ofSuccess(Object, Object)}.
     *
     * @param result the result of the method invocation, represented as a {@link JsonNode}.
     *               Can be {@code null} to represent a successful response with a null result.
     * @param id the ID of the original request this response corresponds to. Can be {@code null}.
     * @return a new {@link JsonRpcResponse} instance representing a successful outcome.
     */
    public static JsonRpcResponse ofSuccess(JsonNode result, @Nullable Object id) {
        return JsonRpcResponse.ofSuccess(result, id);
    }

    /**
     * Creates a new JSON-RPC error response with the given {@link JsonRpcError} and ID.
     * This is a convenience method that delegates to {@link JsonRpcResponse#ofError(JsonRpcError, Object)}.
     *
     * @param error the {@link JsonRpcError} object detailing the error that occurred. Must not be {@code null}.
     * @param id the ID of the original request this response corresponds to. Can be {@code null},
     *           especially if the error occurred before the request ID could be determined.
     * @return a new {@link JsonRpcResponse} instance representing an error outcome.
     */
    public static JsonRpcResponse ofError(JsonRpcError error, @Nullable Object id) {
        return JsonRpcResponse.ofError(error, id);
    }

    /**
     * Converts a {@link JsonRpcResponse} (which can represent either a success or an error)
     * into a final Armeria {@link HttpResponse}.
     * <p>
     * The resulting {@link HttpResponse} will typically have an HTTP status of {@link HttpStatus#OK}
     * and a {@code Content-Type} of {@link MediaType#JSON_UTF_8}, with the serialized {@code rpcResponse}
     * as its body. This is because, in JSON-RPC, application-level errors are conveyed within the
     * JSON-RPC response structure itself, not necessarily through different HTTP status codes.
     * </p>
     * <p>
     * If the serialization of the {@code rpcResponse} into a JSON string fails
     * (an unexpected server-side issue),
     * this method will return an {@link HttpResponse} with {@link HttpStatus#INTERNAL_SERVER_ERROR}
     * and a plain text body indicating the failure.
     * </p>
     *
     * @param rpcResponse The {@link JsonRpcResponse} to be serialized and wrapped in an {@link HttpResponse}.
     *                    Must not be {@code null}.
     * @param mapper The {@link ObjectMapper} to use for serializing the {@code rpcResponse} to JSON.
     *               Must not be {@code null}.
     * @param requestId The ID of the original request. This is used for logging purposes if a serialization
     *                  error occurs. Can be {@code null}.
     * @return An {@link HttpResponse} containing the serialized JSON-RPC response, or an internal server error
     *         response if serialization fails.
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
     * Creates a {@link JsonRpcResponse} representing an error, based on a {@link Throwable} that was caught
     * during the processing of a JSON-RPC request or during the execution of a delegate service method.
     * <p>
     * This method uses {@link #mapThrowableToJsonRpcError(Throwable, String, Object)}
     * to determine the appropriate
     * {@link JsonRpcError} for the given {@code throwable}.
     * </p>
     *
     * @param throwable The {@link Throwable} (exception) that was caught. Must not be {@code null}.
     * @param id The ID from the original JSON-RPC request. Can be {@code null}.
     * @param methodName The name of the JSON-RPC method that was being processed when the throwable occurred.
     *                   Used for logging and error reporting. Must not be {@code null}.
     * @return A new {@link JsonRpcResponse} instance containing the {@link JsonRpcError} derived from
     *         the {@code throwable}.
     */
    public static JsonRpcResponse fromThrowable(Throwable throwable, @Nullable Object id, String methodName) {
        final JsonRpcError error = mapThrowableToJsonRpcError(throwable, methodName, id);
        return JsonRpcResponse.ofError(error, id);
    }

    /**
     * Maps a given {@link Throwable} to an appropriate {@link JsonRpcError}.
     * <p>
     * This method attempts to provide a more specific JSON-RPC error based on the type of the exception:
     * <ul>
     *   <li>If the {@code exception} (or its underlying cause,
     *       after unwrapping {@link CompletionException}) is a
     *       {@link JsonProcessingException}, it's typically mapped to an internal server error related to
     *       delegate response processing.</li>
     *   <li>If it's an {@link IllegalArgumentException}, it's mapped to an invalid request error.</li>
     *   <li>Other exceptions are generally mapped to a generic internal server error.</li>
     * </ul>
     * The original exception's message and type are usually included in the logs and potentially in the
     * resulting {@link JsonRpcError}'s message or data, depending on the specific mapping.
     * </p>
     *
     * @param exception The {@link Throwable} to map. Its {@link CompletionException}
     *                  wrappers will be unwrapped.
     * @param methodName The name of the JSON-RPC method being processed, for context in logging/error messages.
     * @param requestId The ID of the original JSON-RPC request, for context in logging/error messages.
     * @return A {@link JsonRpcError} representing the mapped error condition.
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
