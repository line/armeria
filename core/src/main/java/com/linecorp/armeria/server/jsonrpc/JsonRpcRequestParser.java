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
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.jsonrpc.JsonRpcUtil;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * Parses JSON-RPC requests.
 */
final class JsonRpcRequestParser {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcRequestParser.class);
    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private JsonRpcRequestParser() {} // Utility class

    /**
     * Parses the JSON request body into a list of {@link JsonRpcItemParseResult} objects.
     * Handles both single JSON object requests and batch (JSON array) requests.
     * Returns a list containing error representations for overall parsing errors or invalid items.
     *
     * @param aggregatedRequest The aggregated HTTP request.
     * @return A list of {@link JsonRpcItemParseResult}, potentially containing errors.
     */
    public static List<JsonRpcItemParseResult> parseRequest(AggregatedHttpRequest aggregatedRequest) {
        final String requestBody = aggregatedRequest.contentUtf8();
        logger.trace("Received JSON-RPC request body: {}", requestBody);

        final List<JsonRpcItemParseResult> parsedItems = new LinkedList<>();

        try {
            final JsonNode node = mapper.readTree(requestBody);

            if (node.isArray()) {
                // Batch Request
                if (node.isEmpty()) {
                    logger.warn("Received empty JSON-RPC batch request.");
                    final JsonRpcError error = JsonRpcError
                            .invalidRequest("Received empty JSON-RPC batch request.");
                    parsedItems.add(new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, null)));
                    // Return immediately for empty batch error
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

                final JsonRpcError error = JsonRpcError
                        .invalidRequest("Request must be a JSON object or array.");

                parsedItems.add(new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, null)));
            }
            return parsedItems;
        } catch (JsonProcessingException e) {
            // Handle errors parsing the root JSON structure
            logger.warn("Failed to parse overall JSON-RPC request body: {}", requestBody, e);
            final JsonRpcError error = JsonRpcError
                    .parseError("Invalid JSON received: " + e.getMessage());
            parsedItems.clear(); // Ensure the list is empty before adding the single error
            parsedItems.add(new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, null)));
            return parsedItems;
        } catch (Exception e) {
            // Catch any other unexpected errors during parsing phase
            logger.error("Unexpected error during JSON-RPC request parsing: {}", requestBody, e);
            final JsonRpcError error = JsonRpcError
                    .internalError("Unexpected server error during request parsing");

            parsedItems.clear();
            parsedItems.add(new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, null)));
            return parsedItems;
        }
    }

    /**
     * Helper method to parse a single JsonNode using JsonRpcUtil and handle potential exceptions,
     * returning a JsonRpcItemParseResult.
     */
    private static JsonRpcItemParseResult parseNodeAndHandleError(JsonNode node) {
        final Object id = extractIdFromJsonNode(node); // Extract ID early for error reporting
        try {
            final JsonRpcRequest request = JsonRpcUtil.parseJsonNodeToRequest(node, mapper);
            return new JsonRpcItemParseResult(request);
        } catch (JsonProcessingException e) {
            // Handle parsing errors (invalid JSON structure within the node)
            final JsonRpcError error;
            if (e instanceof MismatchedInputException) {
                // Likely missing required RPC fields
                error = JsonRpcError.invalidRequest("Invalid request object: " + e.getMessage());
            } else {
                error = JsonRpcError.parseError("Failed to parse request object: " + e.getMessage());
            }
            return new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, id));
        } catch (IllegalArgumentException e) {
            // Handle validation errors (invalid version, method, params type etc.)
            final JsonRpcError error = JsonRpcError
                    .invalidRequest("Invalid request object: " + e.getMessage());

            return new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, id));
        } catch (Exception e) {
            // Catch any other unexpected errors during single node processing
            logger.error("Unexpected error parsing JSON-RPC node (id: {}): {}",
                         id, node.toString(), e);
            final JsonRpcError error = JsonRpcError
                    .internalError("Unexpected server error parsing request item");
            return new JsonRpcItemParseResult(JsonRpcResponse.ofError(error, id));
        }
    }

    /**
     * Safely extracts the ID (String, Number, or null) from a JsonNode for error reporting.
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
        // Ignore other types for ID
        return null;
    }
}
