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

import java.math.BigDecimal;
import java.math.BigInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A utility class providing static methods for common JSON-RPC 2.0 message handling tasks,
 * such as parsing, validation, and creation of JSON-RPC requests and responses.
 * This class is not meant to be instantiated.
 */
public final class JsonRpcUtil {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcUtil.class);

    /**
     * Parses an {@link AggregatedHttpResponse} received from a delegate service and converts it into a
     * {@link JsonRpcResponse}.
     * <p>
     * If the {@code delegateResponse} has a successful HTTP status code (2xx):
     * <ul>
     *   <li>If the response content is empty (e.g., HTTP 204 No Content), it is treated as a successful
     *       JSON-RPC response with a {@code null} result.</li>
     *   <li>Otherwise, the response content is parsed as a JSON value using the provided {@link ObjectMapper}.
     *       This JSON value becomes the {@code result} in the {@link JsonRpcResponse}.</li>
     *   <li>If parsing the successful response content fails (e.g., invalid JSON), an internal JSON-RPC error
     *       is generated.</li>
     * </ul>
     * If the {@code delegateResponse} has an error HTTP status code (non-2xx), it is mapped to an appropriate
     * {@link JsonRpcError} using {@link #mapHttpResponseToError(AggregatedHttpResponse, String, Object)}.
     * </p>
     *
     * @param delegateResponse The {@link AggregatedHttpResponse} from the delegate service.
     *                         Must not be {@code null}.
     * @param id The ID from the original JSON-RPC request. This will be used as the ID in the resulting
     *           {@link JsonRpcResponse}. Can be {@code null}.
     * @param methodName The name of the JSON-RPC method that was invoked on the delegate service.
     *                   Used for logging and error reporting. Must not be {@code null}.
     * @param mapper The {@link ObjectMapper} used for parsing the content of successful responses.
     *               Must not be {@code null}.
     * @return A {@link JsonRpcResponse} representing either the successful result
     *         from the delegate or an error.
     *         Never {@code null}.
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
     * Maps an error {@link AggregatedHttpResponse} (non-2xx status) from a delegate service
     * to a specific {@link JsonRpcError}.
     * The mapping logic is as follows:
     * <ul>
     *   <li>HTTP 404 (Not Found) is mapped to {@link JsonRpcErrorCode#METHOD_NOT_FOUND}.</li>
     *   <li>HTTP 400 (Bad Request) is mapped to {@link JsonRpcErrorCode#INVALID_PARAMS}.</li>
     *   <li>Other HTTP client errors (4xx) are mapped to {@link JsonRpcErrorCode#INVALID_REQUEST}.</li>
     *   <li>HTTP server errors (5xx) are mapped to {@link JsonRpcErrorCode#INTERNAL_ERROR}.</li>
     * </ul>
     * The error message in the resulting {@link JsonRpcError} will include details about the invoked method,
     * the HTTP status received, and the content of the HTTP response if available.
     *
     * @param aggregatedHttpResponse The error {@link AggregatedHttpResponse} from the delegate service.
     * @param methodName The name of the JSON-RPC method that was invoked.
     * @param requestId The ID of the original JSON-RPC request, for inclusion in error messages.
     * @return A {@link JsonRpcError} corresponding to the HTTP error status.
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
     * Parses a single {@link JsonNode}, expected to represent a JSON-RPC request,
     * into a {@link JsonRpcRequest} object.
     * This method also performs basic validation against the JSON-RPC 2.0 specification:
     * <ul>
     *   <li>The input {@code itemNode} must be a JSON object.</li>
     *   <li>The "jsonrpc" member must be present and equal to "2.0".</li>
     *   <li>The "method" member must be present and non-empty.</li>
     *   <li>If the "params" member is present and not null, it must be a JSON Array or a JSON Object.</li>
     * </ul>
     * If any of these validations fail, an {@link IllegalArgumentException} is thrown.
     * If the {@code itemNode} cannot be deserialized into a {@link JsonRpcRequest}
     * (e.g., due to type mismatches
     * for required fields), a {@link JsonProcessingException} is thrown.
     *
     * @param itemNode The {@link JsonNode} to parse. Must be a JSON object representing a single request.
     *                 Must not be {@code null}.
     * @param mapper The {@link ObjectMapper} to use for deserializing the node into a {@link JsonRpcRequest}.
     *               Must not be {@code null}.
     * @return The successfully parsed and validated {@link JsonRpcRequest}.
     * @throws JsonProcessingException if deserialization of the {@code itemNode} fails.
     * @throws IllegalArgumentException if the {@code itemNode} is not a JSON object or if it fails
     *                                  JSON-RPC 2.0 validation rules.
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
            } else {
                throw new IllegalArgumentException("ID MUST contain a String, Number," +
                                                   " or NULL value if included");
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
        if (!"2.0".equals(rpcRequest.jsonRpcVersion())) {
            throw new IllegalArgumentException("Invalid JSON-RPC version: " + rpcRequest.jsonRpcVersion());
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

    /**
     * Creates a JSON string representation of a JSON-RPC 2.0 request.
     *
     * @param method The name of the method to be invoked. Must not be {@code null}.
     * @param params The parameters for the method. Can be any object that Jackson can serialize to a JSON Array
     *               or JSON Object, or {@code null} if no parameters are needed.
     * @param id The request identifier. Can be a {@link String}, a {@link Number}, or {@code null}
     *           (for notifications).
     *           If {@code null}, the "id" field in the JSON request will be explicitly set to JSON null.
     * @param mapper The {@link ObjectMapper} to use for serializing the request to a JSON string.
     *               Must not be {@code null}.
     * @return A JSON string representing the JSON-RPC request.
     * @throws IllegalArgumentException if the provided {@code id} is not a {@link String},
     *                                  {@link Number}, or {@code null}.
     *                                  (Note: This exception is caught internally and re-thrown as a
     *                                  {@link RuntimeException} wrapping a {@link JsonProcessingException}
     *                                  if JSON serialization itself fails, but the initial ID type check
     *                                  can throw {@link IllegalArgumentException} directly).
     * @throws RuntimeException         if JSON serialization fails
     *                                  (wrapping the original {@link JsonProcessingException}).
     */
    public static String createJsonRpcRequestJsonString(String method,
                                                        @Nullable Object params,
                                                        @Nullable Object id,
                                                        ObjectMapper mapper) {
        final JsonNodeFactory factory = mapper.getNodeFactory();
        final ObjectNode requestJson = factory.objectNode();

        requestJson.put("jsonrpc", "2.0");
        requestJson.put("method", method);
        if (params != null) {
            requestJson.set("params", mapper.valueToTree(params));
        }
        if (id == null) {
            requestJson.set("id", factory.nullNode());
        } else if (id instanceof String) {
            requestJson.put("id", (String) id);
        } else if (id instanceof Number) {
            final Number numId = (Number) id;
            if (id instanceof Integer) {
                requestJson.put("id", numId.intValue());
            } else if (id instanceof Long) {
                requestJson.put("id", numId.longValue());
            } else if (id instanceof Double) {
                requestJson.put("id", numId.doubleValue());
            } else if (id instanceof Float) {
                requestJson.put("id", numId.floatValue());
            } else if (id instanceof BigDecimal) {
                requestJson.set("id", factory.numberNode((BigDecimal) id));
            } else if (id instanceof BigInteger) {
                requestJson.set("id", factory.numberNode((BigInteger) id));
            } else {
                requestJson.put("id", numId.doubleValue());
            }
        } else {
            throw new IllegalArgumentException("Unsupported ID type: " + id.getClass().getName());
        }
        try {
            return mapper.writeValueAsString(requestJson);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private JsonRpcUtil() {}
}
