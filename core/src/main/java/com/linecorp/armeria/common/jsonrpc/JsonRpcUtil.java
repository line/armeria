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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Utility class for JSON-RPC handling.
 */
public final class JsonRpcUtil {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcUtil.class);

    /**
     * Parses the {@link AggregatedHttpResponse} from a delegate service into a {@link JsonRpcResponse}.
     * Handles both successful responses (parsing the content as JSON) and error responses
     * (mapping HTTP status to {@link JsonRpcError}).
     *
     * @param delegateResponse The aggregated response from the delegate service.
     * @param id The ID from the original JSON-RPC request.
     * @param methodName The name of the JSON-RPC method being processed.
     * @param mapper The {@link ObjectMapper} used for parsing successful responses.
     * @return The corresponding {@link JsonRpcResponse} (success or error).
     */
    public static JsonRpcResponse parseDelegateResponse(AggregatedHttpResponse delegateResponse,
                                                          @Nullable Object id, String methodName,
                                                          ObjectMapper mapper) {
        final HttpStatus status = delegateResponse.status();
        final String content = delegateResponse.contentUtf8();
        logger.debug("Parsing delegate response for JSON-RPC method '{}' (id: {}): status={}, content='{}'",
                     methodName, id, status,
                     content.length() > 500 ? content.substring(0, 500) + "..." : content);

        if (status.isSuccess()) {
            try {
                // Allow empty content for successful responses (e.g., 204 No Content treated as null result)
                if (content.isEmpty()) {
                    return JsonRpcResponse.ofSuccess(null, id);
                }
                final JsonNode resultNode = mapper.readTree(content);
                return JsonRpcResponse.ofSuccess(resultNode, id);
            } catch (JsonProcessingException e) {
                logger.warn("Failed to parse delegate response content as JSON for method '{}' (id: {}). " +
                            "Treating as internal error. Content: {}",
                            methodName, id, content, e);
                final JsonRpcError error = JsonRpcError.internalError(
                        "Failed to parse successful delegate response as JSON");
                return JsonRpcResponse.ofError(error, id);
            }
        } else {
            // Map HTTP error status to JSON-RPC error
            final JsonRpcError error = mapHttpResponseToError(delegateResponse, methodName, id);
            return JsonRpcResponse.ofError(error, id);
        }
    }

    /**
     * Maps an error {@link AggregatedHttpResponse} from a delegate service to a specific {@link JsonRpcError}.
     */
    private static JsonRpcError mapHttpResponseToError(AggregatedHttpResponse aggregatedHttpResponse,
                                                         String methodName, @Nullable Object requestId) {
        final HttpStatus status = aggregatedHttpResponse.status();
        final String content = aggregatedHttpResponse.contentUtf8();

        logger.warn("Mapping HTTP error response " +
                    "from delegate for method '{}' (id: {}): status={}, content='{}'",
                    methodName, requestId, status,
                    content.length() > 500 ? content.substring(0, 500) + "..." : content);

        if (status == HttpStatus.NOT_FOUND) {
            return JsonRpcError.methodNotFound(
                    "Method '" + methodName + "' not found at delegate service (returned 404)");
        } else if (status == HttpStatus.BAD_REQUEST) {
            String message = "Invalid parameters for method '" + methodName + "' (delegate returned 400)";
            if (!content.isEmpty()) {
                message += ": " + content;
            }
            return JsonRpcError.invalidParams(message);
        } else if (status.isClientError()) {
            String message = "Client error during delegate execution for method '" + methodName + "' " +
                             "(delegate returned " + status + ')';
            if (!content.isEmpty()) {
                message += ": " + content;
            }
            return JsonRpcError.invalidRequest(message);
        } else { // Server errors (5xx)
            String message = "Internal server error during delegate execution for method '" +
                             methodName + "' (delegate returned " + status + ')';
            if (!content.isEmpty()) {
                message += ": " + content;
            }
            return JsonRpcError.internalError(message);
        }
    }

    /**
     * Parses a single {@link JsonNode} representing a potential JSON-RPC request into a {@link JsonRpcRequest}.
     * Throws exceptions if parsing or validation fails.
     *
     * @param itemNode The JSON node representing a potential JSON-RPC request.
     * @param mapper The {@link ObjectMapper} used for parsing.
     * @return The successfully parsed {@link JsonRpcRequest}.
     * @throws JsonProcessingException if the node cannot be parsed into a {@link JsonRpcRequest}.
     * @throws IllegalArgumentException if the parsed request fails JSON-RPC 2.0 validation.
     */
    public static JsonRpcRequest parseJsonNodeToRequest(JsonNode itemNode, ObjectMapper mapper)
            throws JsonProcessingException, IllegalArgumentException {
        if (!itemNode.isObject()) {
            throw new IllegalArgumentException("Request item must be a JSON object.");
        }

        Object idForErrorLogging = null; // Used only for logging in case of exceptions
        final JsonNode idNode = itemNode.get("id");
        if (idNode != null) {
            if (idNode.isTextual()) {
                idForErrorLogging = idNode.asText();
            } else if (idNode.isNumber()) {
                idForErrorLogging = idNode.numberValue();
            } else if (idNode.isNull()) {
                idForErrorLogging = null;
            }
        }

        final JsonRpcRequest rpcRequest;
        try {
            rpcRequest = mapper.treeToValue(itemNode, JsonRpcRequest.class);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse JSON node into JsonRpcRequest (id: {}): {}",
                        idForErrorLogging, itemNode, e);
            throw e;
        }

        // Basic JSON-RPC 2.0 Validation - Throw IllegalArgumentException for validation failures
        if (!"2.0".equals(rpcRequest.jsonrpc())) {
            throw new IllegalArgumentException("Invalid JSON-RPC version: " + rpcRequest.jsonrpc());
        }
        if (rpcRequest.method() == null || rpcRequest.method().isEmpty()) {
            throw new IllegalArgumentException("JSON-RPC request 'method' is missing or empty");
        }

        final JsonNode paramsNode = rpcRequest.params();
        if (paramsNode != null && !paramsNode.isNull() && !paramsNode.isObject() && !paramsNode.isArray()) {
            throw new IllegalArgumentException("JSON-RPC request 'params' must be an object or an array, " +
                                               "but was: " + paramsNode.getNodeType());
        }

        logger.debug("Parsed JSON-RPC request: method={}, id={}", rpcRequest.method(), rpcRequest.id());
        return rpcRequest;
    }

    private JsonRpcUtil() {}
}
