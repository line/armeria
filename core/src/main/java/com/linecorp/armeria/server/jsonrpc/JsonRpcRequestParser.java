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

import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.jsonrpc.JsonRpcUtil;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * Parses JSON-RPC requests.
 */
@UnstableApi
final class JsonRpcRequestParser {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcRequestParser.class);
    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    /**
     * Parses the JSON request body from an {@link AggregatedHttpRequest} into a list of
     * {@link JsonRpcItemParseResult} objects.
     *
     * <p>This method handles both single JSON object requests and batch (JSON array) requests.
     * <ul>
     *     <li>If the overall JSON structure is invalid (e.g., not a JSON object or array, or malformed JSON),
     *         the returned list will contain a single {@link JsonRpcItemParseResult}
     *         representing this top-level parsing error.</li>
     *     <li>For batch requests, if an individual item within the batch fails to parse or validate,
     *         the corresponding {@link JsonRpcItemParseResult}
     *         in the list will represent that specific item\'s error.
     *         If the batch array itself is empty, a specific error indicating an empty batch is returned.</li>
     * </ul>
     * The method ensures that even in cases of severe parsing failures, a list containing at least one error
     * representation is returned, facilitating consistent error handling downstream.
     *
     * @param aggregatedRequest The {@link AggregatedHttpRequest} containing the JSON-RPC request content.
     *                          The content is expected to be UTF-8 encoded JSON. Must not be {@code null}.
     * @return A non-null {@link List} of {@link JsonRpcItemParseResult} objects. Each object in the list
     *         corresponds to an item in the original request (or a single item if it wasn\'t a batch).
     *         The list will contain at least one element, even in case of errors.
     */
    public static List<JsonRpcItemParseResult> parseRequest(AggregatedHttpRequest aggregatedRequest) {
        requireNonNull(aggregatedRequest, "aggregatedRequest");

        final String requestBody = aggregatedRequest.contentUtf8();
        logger.trace("Received JSON-RPC request body: {}", requestBody);

        final List<JsonRpcItemParseResult> parsedItems = new LinkedList<>();

        try {
            final JsonNode node = mapper.readTree(requestBody);

            if (node.isArray()) {
                // Batch Request
                if (node.isEmpty()) {
                    logger.warn("Received empty JSON-RPC batch request.");
                    final JsonRpcError error =
                            JsonRpcError.INVALID_REQUEST.withData("Received empty JSON-RPC batch request.");
                    parsedItems.add(new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, null)));
                    return parsedItems;
                }
                for (JsonNode itemNode : node) {
                    parsedItems.add(parseNodeAndHandleError(itemNode));
                }
            } else if (node.isObject()) {
                // Single Request
                parsedItems.add(parseNodeAndHandleError(node));
            } else {
                logger.warn("Invalid JSON-RPC request: Request must be a JSON object or array. Body: {}",
                        requestBody);

                final JsonRpcError error =
                        JsonRpcError.INVALID_REQUEST.withData("Request must be a JSON object or array.");

                parsedItems.add(new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, null)));
            }
            return parsedItems;
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse overall JSON-RPC request body: {}", requestBody, e);
            final JsonRpcError error =
                    JsonRpcError.PARSE_ERROR.withData("Invalid JSON received: " + e.getMessage());
            parsedItems.clear(); // Ensure the list is empty before adding the single error
            parsedItems.add(new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, null)));
            return parsedItems;
        } catch (Exception e) {
            logger.error("Unexpected error during JSON-RPC request parsing: {}", requestBody, e);
            final JsonRpcError error =
                    JsonRpcError.INTERNAL_ERROR.withData("Unexpected server error during request parsing");

            parsedItems.clear();
            parsedItems.add(new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, null)));
            return parsedItems;
        }
    }

    /**
     * Helper method to parse a single {@link JsonNode} representing a JSON-RPC request item.
     * It attempts to convert the node into a {@link JsonRpcRequest} using {@link JsonRpcUtil}.
     * If parsing or validation (e.g., missing required fields, incorrect types) fails,
     * this method catches the exception and wraps it in a {@link JsonRpcItemParseResult}
     * containing an appropriate {@link JsonRpcResponse} error. The ID of the request item is extracted
     * from the node if possible, to be included in any error response.
     *
     * @param node The {@link JsonNode} to parse. Assumed to be a JSON object representing a single
     *             JSON-RPC request. Must not be {@code null}.
     * @return A {@link JsonRpcItemParseResult} which either holds the successfully parsed
     *         {@link JsonRpcRequest} or a {@link JsonRpcResponse} detailing the parsing/validation error.
     *         Will not be {@code null}.
     */
    private static JsonRpcItemParseResult parseNodeAndHandleError(JsonNode node) {
        final Object id = extractIdFromJsonNode(node);
        try {
            final JsonRpcRequest request = JsonRpcUtil.parseJsonNodeToRequest(node, mapper);
            return new JsonRpcItemParseResult(request);
        } catch (JsonProcessingException e) {
            final JsonRpcError error;
            if (e instanceof MismatchedInputException) {
                error = JsonRpcError.INVALID_REQUEST.withData("Invalid request object: " + e.getMessage());
            } else {
                error = JsonRpcError.PARSE_ERROR.withData("Failed to parse request object: " + e.getMessage());
            }
            return new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, id));
        } catch (IllegalArgumentException | NullPointerException e) {
            final JsonRpcError error =
                    JsonRpcError.INVALID_REQUEST.withData("Invalid request object: " + e.getMessage());
            return new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, id));
        } catch (Exception e) {
            logger.error("Unexpected error parsing JSON-RPC node (id: {}): {}",
                    id, node.toString(), e);
            final JsonRpcError error =
                    JsonRpcError.INTERNAL_ERROR.withData("Unexpected server error parsing request item");
            return new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, id));
        }
    }

    /**
     * Safely extracts the ID from a {@link JsonNode} that is expected to represent a JSON-RPC request item.
     * The JSON-RPC specification allows the "id" field to be a String, a Number, or null.
     * This method attempts to retrieve the "id" field and return its value if it\'s one of these types.
     * If the {@code itemNode} is not a JSON object, or if the "id" field is missing, explicitly null,
     * or not a String or Number, this method returns {@code null}. This is crucial for constructing
     * correct error responses, as the ID must be included if present and valid.
     *
     * @param itemNode The {@link JsonNode} from which to extract the ID. Expected to be a JSON object.
     *                 Must not be {@code null}.
     * @return The extracted ID as a {@link String}, {@link Number}, or {@code null} if the ID is absent,
     *         explicitly null, or of an unsupported type.
     */
    @Nullable
    private static Object extractIdFromJsonNode(JsonNode itemNode) {
        if (!itemNode.isObject()) {
            return null;
        }
        final JsonNode idNode = itemNode.get("id");
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        if (idNode.isTextual()) {
            return idNode.asText();
        }
        if (idNode.isNumber()) {
            return idNode.numberValue();
        }
        return null;
    }

    private JsonRpcRequestParser() {}
}
