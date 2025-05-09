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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponseFactory;
import com.linecorp.armeria.common.jsonrpc.JsonRpcUtil;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Builds a new {@link HttpService}.
 * This service acts as a dispatcher, routing incoming JSON-RPC requests
 * based on their method name to the appropriate delegate service.
 */
@UnstableApi
public class JsonRpcServiceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcServiceBuilder.class);
    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    /**
     * Represents the result of parsing a single item from a JSON-RPC request (which could be part of a batch).
     * It holds either a successfully parsed {@link JsonRpcRequest} or a {@link JsonRpcResponse} representing
     * a parsing/validation error specific to that item.
     */
    private static final class JsonRpcItemParseResult {

        @Nullable
        private final JsonRpcRequest request;

        @Nullable
        private final JsonRpcResponse errorResponse;

        /**
         * Creates a new instance representing a successfully parsed request.
         */
        JsonRpcItemParseResult(JsonRpcRequest request) {
            this.request = requireNonNull(request, "request");
            this.errorResponse = null;
        }

        /**
         * Creates a new instance representing a parsing or validation error.
         */
        JsonRpcItemParseResult(JsonRpcResponse errorResponse) {
            requireNonNull(errorResponse, "errorResponse");
            // Check if the error object exists, as JsonRpcResponse always represents a response
            if (errorResponse.error() == null) {
                throw new IllegalArgumentException(
                        "errorResponse must contain an error object: " + errorResponse);
            }
            this.request = null;
            this.errorResponse = errorResponse;
        }

        /**
         * Returns {@code true} if this instance represents an error.
         */
        boolean isError() {
            return errorResponse != null;
        }

        /**
         * Returns the successfully parsed {@link JsonRpcRequest}, or {@code null} if this represents an error.
         */
        @Nullable
        JsonRpcRequest request() {
            return request;
        }

        /**
         * Returns the {@link JsonRpcResponse} representing an error, or {@code null} if this represents a
         * successfully parsed request.
         */
        @Nullable
        JsonRpcResponse errorResponse() {
            return errorResponse;
        }
    }

    private final Map<String, HttpService> cachedRoutes = new LinkedHashMap<>();
    private final Map<String, Object> annotatedServices = new LinkedHashMap<>();
    private final ServerBuilder serverBuilder;

    /**
     * Creates a new {@link JsonRpcServiceBuilder} associated with the given {@link ServerBuilder}.
     *
     * @param serverBuilder The {@link ServerBuilder} to which annotated services will be added.
     */
    public JsonRpcServiceBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = serverBuilder;
    }

    /**
     * Adds an {@link HttpService} that handles JSON-RPC requests under the specified prefix.
     * Note: This method is primarily for internal use or advanced scenarios.
     * Prefer {@link #addAnnotatedService(String, Object)}.
     *
     * @param prefix The path prefix for this service.
     * @param service The {@link HttpService} to handle requests under this prefix.
     * @return this {@link JsonRpcServiceBuilder} for chaining.
     */
    public JsonRpcServiceBuilder addService(String prefix, HttpService service) {
        requireNonNull(prefix, "prefix");
        requireNonNull(service, "service");
        cachedRoutes.put(prefix, service);
        return this;
    }

    /**
     * Adds an annotated service object that handles JSON-RPC requests under the specified prefix.
     * Methods within the service will be registered with the {@link ServerBuilder} when {@link #build()} is
     * called.
     *
     * @param prefix The path prefix for this annotated service.
     * @param service The annotated service object.
     * @return this {@link JsonRpcServiceBuilder} for chaining.
     */
    public JsonRpcServiceBuilder addAnnotatedService(String prefix, Object service) {
        requireNonNull(prefix, "prefix");
        requireNonNull(service, "service");
        annotatedServices.put(prefix, service);
        return this;
    }

    /**
     * Builds the final {@link HttpService}.
     * This registers all added annotated services with the {@link ServerBuilder} and
     * returns the main dispatching {@link HttpService}.
     *
     * @return The configured {@link HttpService} that handles JSON-RPC dispatching.
     */
    public HttpService build() {
        // Register all annotated services with the server builder.
        for (Map.Entry<String, ?> entry : annotatedServices.entrySet()) {
            serverBuilder.annotatedService(entry.getKey(), entry.getValue())
                         .annotatedServiceExtensions(Collections.emptyList(),
                                                     Collections.emptyList(),
                                                     Collections.singletonList(new JsonRpcExceptionHandler()));
        }

        return (ctx, req) -> HttpResponse.of(
                req.aggregate()
                   .thenCompose(aggregatedRequest ->
                                        handleAggregatedRequest(ctx, req, aggregatedRequest)));
    }

    /**
     * Handles the aggregated HTTP request, parsing JSON-RPC, routing, and generating the response.
     */
    private CompletableFuture<HttpResponse> handleAggregatedRequest(
            ServiceRequestContext ctx, HttpRequest originalRequest, AggregatedHttpRequest aggregatedRequest) {
        final List<JsonRpcItemParseResult> parsedItems = parseRequest(aggregatedRequest);
        final List<CompletableFuture<HttpResponse>> futures = new LinkedList<>();

        for (JsonRpcItemParseResult item : parsedItems) {
            if (item.isError()) {
                final JsonRpcResponse errorResponse = item.errorResponse();
                assert errorResponse != null;
                futures.add(JsonRpcResponseFactory.toHttpResponseFuture(
                        errorResponse, mapper, errorResponse.id()));
            } else {
                final JsonRpcRequest rpcRequest = item.request();
                assert rpcRequest != null;

                if (rpcRequest.isNotification()) {
                    // Handle notification request
                    try {
                        final HttpResponse delegateResponseFuture = route(ctx, originalRequest, rpcRequest);
                        delegateResponseFuture.aggregate().exceptionally(ex -> {
                            logger.warn("Error executing notification delegate for method '{}': {}",
                                        rpcRequest.method(), ex.getMessage(), ex);
                            return null;
                        });
                    } catch (Exception e) {
                        logger.warn("Failed to route or start execution for notification method " +
                                    "'{}': {}", rpcRequest.method(), e.getMessage(), e);
                    }
                } else {
                    // Handle regular request
                    try {
                        final HttpResponse delegateResponseFuture = route(ctx, originalRequest, rpcRequest);
                        futures.add(delegateResponseFuture.aggregate().handle(
                                (delegatedAggregatedResponse, throwable) -> {
                                    final JsonRpcResponse rpcResponse;
                                    if (throwable != null) {
                                        logger.warn("Error executing delegate or aggregating response for " +
                                                    "method '{}' (id: {}): {}",
                                                    rpcRequest.method(),
                                                    rpcRequest.id(),
                                                    throwable.getMessage(),
                                                    throwable);

                                        rpcResponse = JsonRpcResponseFactory.fromThrowable(
                                                throwable, rpcRequest.id(), rpcRequest.method());
                                    } else {
                                        rpcResponse = JsonRpcResponseFactory.fromDelegateResponse(
                                                delegatedAggregatedResponse, rpcRequest.id(),
                                                rpcRequest.method(), mapper);
                                    }
                                    return JsonRpcResponseFactory.toHttpResponse(
                                            rpcResponse, mapper, rpcRequest.id());
                                }
                        ));
                    } catch (JsonRpcServiceNotFoundException e) {
                        logger.warn("No service found matching JSON-RPC method path: {}", e.getLookupPath());

                        final JsonRpcError error = JsonRpcError.methodNotFound("Method not found: " +
                                                                               e.getLookupPath());
                        futures.add(JsonRpcResponseFactory.toHttpResponseFuture(
                                JsonRpcResponse.ofError(error, rpcRequest.id()), mapper, rpcRequest.id()));
                    } catch (Exception e) {
                        logger.error("Unexpected error routing/processing JSON-RPC request item: {}",
                                     rpcRequest, e);
                        final JsonRpcError error = JsonRpcError.internalError(
                                "Unexpected server error processing request item");
                        futures.add(JsonRpcResponseFactory.toHttpResponseFuture(
                                JsonRpcResponse.ofError(error, rpcRequest.id()), mapper, rpcRequest.id()));
                    }
                }
            }
        }

        if (futures.isEmpty()) {
            // This happens if the batch contained ONLY notifications.
            // (Empty array case is handled by JsonRpcRequestParseException)
            return UnmodifiableFuture.completedFuture(HttpResponse.of(HttpStatus.OK));
        }

        // If the original request was not an array but resulted in a single future
        // (successful single request),
        // return the single response directly without wrapping in an array.
        if (parsedItems.size() == 1) {
            return futures.get(0);
        }

        // Otherwise (batch request or single request with error during parsing),
        // return an array response.
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    final List<JsonNode> responseNodes = futures.stream()
                            .map(future -> {
                                try {
                                    final HttpResponse aggResp = future.join();
                                    return mapper.readTree(aggResp.aggregate()
                                                                  .join()
                                                                  .contentUtf8());
                                } catch (Exception e) {
                                    logger.warn("Failed to aggregate/parse individual JSON-RPC " +
                                              "response within batch," +
                                                "returning internal error node", e);

                                    return mapper.valueToTree(
                                            JsonRpcResponse.ofError(
                                            JsonRpcError
                                                    .internalError("Failed to process response"),
                                            null));
                                }
                            })
                            .collect(Collectors.toList());
                    try {
                        return HttpResponse.ofJson(MediaType.JSON_UTF_8, responseNodes);
                    } catch (Exception e) { // Catch potential exceptions from ofJson
                        logger.error("Failed to serialize final batch JSON-RPC response", e);
                        // This is a server-side issue, respond with a single internal error
                        final JsonRpcResponse errResp = JsonRpcResponse.ofError(
                                JsonRpcError
                                        .internalError("Failed to create batch response"),
                                null);
                        return JsonRpcResponseFactory.toHttpResponse(errResp, mapper, null);
                    }
                });
    }

    /**
     * Parses the JSON request body into a list of {@link JsonRpcItemParseResult} objects.
     * Handles both single JSON object requests and batch (JSON array) requests.
     * Returns a list containing error representations for overall parsing errors or invalid items.
     *
     * @param aggregatedRequest The aggregated HTTP request.
     * @return A list of {@link JsonRpcItemParseResult}, potentially containing errors.
     */
    private List<JsonRpcItemParseResult> parseRequest(AggregatedHttpRequest aggregatedRequest) {
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
            parsedItems.clear(); // Ensure list is empty before adding the single error
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
    private JsonRpcItemParseResult parseNodeAndHandleError(JsonNode node) {
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

    /**
     * Routes the validated {@link JsonRpcRequest} to the appropriate delegate {@link HttpService}.
     * It finds the service based on the request context's mapped path (prefix) and the JSON-RPC method name,
     * then constructs a new {@link HttpRequest} with adjusted path and parameters for the delegate service.
     *
     * @param context The {@link ServiceRequestContext} of the incoming request.
     * @param originalHttpRequest The original {@link HttpRequest}.
     * @param jsonRpcRequest The parsed and validated {@link JsonRpcRequest}.
     * @return An {@link HttpResponse} produced by the delegate service.
     * @throws Exception if an error occurs during service lookup or invocation.
     */
    private HttpResponse route(ServiceRequestContext context, HttpRequest originalHttpRequest,
                               JsonRpcRequest jsonRpcRequest) throws Exception {
        final String targetPath = context.mappedPath() +
                                  (jsonRpcRequest.method().startsWith("/") ? jsonRpcRequest.method()
                                                                           : '/' + jsonRpcRequest.method());

        final HttpService targetService = findService(context, targetPath);

        // Build delegate headers with the actual target path.
        final RequestHeaders delegateHeaders = originalHttpRequest.headers().toBuilder()
                .path(targetPath)
                .build();

        final HttpRequest delegateRequest;
        if (jsonRpcRequest.params() != null) {
            final String paramsJson = mapper.writeValueAsString(jsonRpcRequest.params());
            delegateRequest = HttpRequest.of(delegateHeaders, HttpData.ofUtf8(paramsJson));
        } else {
            delegateRequest = HttpRequest.of(delegateHeaders, HttpData.empty());
        }

        // Serve using a new context derived from the delegate request.
        return targetService.serve(ServiceRequestContext.of(delegateRequest), delegateRequest);
    }

    /**
     * Finds the appropriate {@link HttpService} to handle a request based on the target path
     * derived from the JSON-RPC method name. Uses a cache for efficiency.
     *
     * @param context The {@link ServiceRequestContext} (used only to access server config).
     * @param targetPath The target path (e.g., "/subtractByName") to look up in server configuration.
     * @return The {@link HttpService} responsible for handling the request.
     * @throws JsonRpcServiceNotFoundException if no service is found for the target path.
     */
    private HttpService findService(ServiceRequestContext context, String targetPath)
            throws JsonRpcServiceNotFoundException {
        // Use the targetPath directly for cache lookup and server config search.
        final String lookupPath = targetPath.startsWith("/") ? targetPath : '/' + targetPath;
        final String prefix = context.mappedPath();

        HttpService result = cachedRoutes.get(lookupPath) != null ? cachedRoutes.get(lookupPath)
                                                                  : cachedRoutes.get(prefix);
        if (result == null) {
            // Search server config using the exact lookupPath.
            result = context.config().server().serviceConfigs().stream()
                    .filter(serviceConfig -> serviceConfig.route().patternString().equals(lookupPath))
                    .map(ServiceConfig::service)
                    .findFirst()
                    .orElse(null);

            if (result == null) {
                // Throw a specific exception for service not found, 404 Not Found
                throw new JsonRpcServiceNotFoundException(
                        "No service registered for the method path: " + lookupPath, lookupPath);
            }

            cachedRoutes.put(lookupPath, result);
            logger.debug("Cached service for path: {}", lookupPath);
        } else {
            logger.trace("Using cached service for path: {}", lookupPath);
        }
        return result;
    }
}
