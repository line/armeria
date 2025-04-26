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
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Builds a new {@link HttpService}s using Json rpc
 * into a single JSON-RPC endpoint. This service acts as a dispatcher, routing incoming JSON-RPC requests
 * based on their method name to the appropriate delegate service.
 */
public class JsonRpcServiceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcServiceBuilder.class);
    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private static class ParsedRpcItem {
        @Nullable
        final JsonRpcRequest request;
        @Nullable
        final JsonRpcResponse errorResponse;

        ParsedRpcItem(JsonRpcRequest request) {
            this.request = requireNonNull(request, "request");
            this.errorResponse = null;
        }

        ParsedRpcItem(JsonRpcResponse errorResponse) {
            this.request = null;
            this.errorResponse = requireNonNull(errorResponse, "errorResponse");
        }

        boolean isError() {
            return errorResponse != null;
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
     * The method name from the JSON-RPC request will be appended to the prefix to find the target service.
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
     * This registers all added annotated services with the associated {@link ServerBuilder} and
     * returns the main dispatching {@link HttpService}.
     *
     * @return The configured {@link HttpService} that handles JSON-RPC dispatching.
     */
    public HttpService build() {
        for (Map.Entry<String, ?> entry : annotatedServices.entrySet()) {
            serverBuilder.annotatedService(entry.getKey(), entry.getValue())
                         .annotatedServiceExtensions(Collections.emptyList(),
                                                     Collections.emptyList(),
                                                     Collections.singletonList(new JsonRpcExceptionHandler()));
        }

        return (ctx, req) -> HttpResponse.of(req.aggregate().thenCompose(aggregatedRequest -> {
            final boolean wasRequestJsonArray;
            try {
                wasRequestJsonArray = mapper.readTree(aggregatedRequest.contentUtf8()).isArray();
            } catch (JsonProcessingException e) {
                logger.warn("Failed to parse JSON-RPC request body: {}", aggregatedRequest.contentUtf8(), e);
                final JsonRpcError error = JsonRpcError.parseError(
                        "Invalid JSON received: " + e.getMessage());
                final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(error, null);
                return createErrorHttpResponseFuture(rpcResponse, null);
            }

            try {
                final List<ParsedRpcItem> parsedItems = parseRequest(aggregatedRequest);
                final List<CompletableFuture<HttpResponse>> futures = new LinkedList<>();

                for (ParsedRpcItem item : parsedItems) {
                    if (item.isError()) {
                        assert item.errorResponse != null;
                        futures.add(createErrorHttpResponseFuture(item.errorResponse, item.errorResponse.id()));
                    } else {
                        final JsonRpcRequest rpcRequest = item.request;
                        assert rpcRequest != null;

                        if (rpcRequest.isNotification()) {
                            try {
                                final HttpResponse delegateResponseFuture = route(ctx, req, rpcRequest);
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
                                final HttpResponse delegateResponseFuture = route(ctx, req, rpcRequest);
                                futures.add(delegateResponseFuture.aggregate().handle(
                                        (delegatedAggregatedResponse, throwable) ->
                                                processResponse(delegatedAggregatedResponse, throwable,
                                                                rpcRequest.method(), rpcRequest.id())
                                ));
                            } catch (JsonRpcServiceNotFoundException e) {
                                logger.warn("No service found matching JSON-RPC method path: {}",
                                            e.getLookupPath());
                                final JsonRpcError error = JsonRpcError.methodNotFound(
                                        "Method not found: " + e.getLookupPath());
                                futures.add(createErrorHttpResponseFuture(
                                        JsonRpcResponse.ofError(error, rpcRequest.id()), rpcRequest.id()));
                            } catch (Exception e) {
                                logger.error("Unexpected error routing/processing JSON-RPC request item: {}",
                                             rpcRequest, e);
                                final JsonRpcError error = JsonRpcError.internalError(
                                        "Unexpected server error processing request item");
                                futures.add(createErrorHttpResponseFuture(
                                        JsonRpcResponse.ofError(error, rpcRequest.id()), rpcRequest.id()));
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
                if (!wasRequestJsonArray && futures.size() == 1) {
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
                                // Use HttpResponse.ofJson for batch responses
                                return HttpResponse.ofJson(MediaType.JSON_UTF_8, responseNodes);
                            } catch (Exception e) { // Catch potential exceptions from ofJson
                                logger.error("Failed to serialize final batch JSON-RPC response", e);
                                // This is a server-side issue, respond with a single internal error
                                final JsonRpcResponse errResp = JsonRpcResponse.ofError(
                                        JsonRpcError
                                                .internalError("Failed to create batch response"),
                                        null);
                                return createSyncHttpResponse(errResp, null);
                            }
                        });
            } catch (JsonRpcRequestParseException e) {
                logger.warn("Failed to parse incoming JSON-RPC request structure", e);
                return createErrorHttpResponseFuture(e.getErrorResponse(), e.getRequestId());
            }
        }));
    }

    /**
     * Parses the JSON request body into a list of {@link ParsedRpcItem}.
     * Handles both single JSON object requests and batch (JSON array) requests.
     * For batch requests, invalid items are represented as {@link ParsedRpcItem} with an error response,
     * allowing processing of other valid items.
     *
     * @param aggregatedRequest The aggregated HTTP request.
     * @return A list of {@link ParsedRpcItem}.
     * @throws JsonRpcRequestParseException if the overall structure is invalid (e.g., not JSON, empty array).
     */
    private List<ParsedRpcItem> parseRequest(AggregatedHttpRequest aggregatedRequest)
            throws JsonRpcRequestParseException {
        final String requestBody = aggregatedRequest.contentUtf8();
        logger.trace("Received JSON-RPC request body: {}", requestBody);
        final List<ParsedRpcItem> parsedItems = new LinkedList<>();

        try {
            final JsonNode node = mapper.readTree(requestBody);

            if (node.isArray()) {
                // Batch Request
                if (node.isEmpty()) {
                    final JsonRpcError error = JsonRpcError.invalidRequest(
                            "Received empty JSON-RPC batch request.");
                    throw new JsonRpcRequestParseException(new IllegalArgumentException(error.message()),
                                                         JsonRpcResponse.ofError(error, null), null);
                }
                for (JsonNode itemNode : node) {
                    parsedItems.add(parseSingleNode(itemNode)); // Parse each item individually
                }
            } else if (node.isObject()) {
                // Single Request
                parsedItems.add(parseSingleNode(node));
            } else {
                final JsonRpcError error = JsonRpcError.invalidRequest(
                        "Request must be a JSON object or array.");
                throw new JsonRpcRequestParseException(new IllegalArgumentException(error.message()),
                                                     JsonRpcResponse.ofError(error, null), null);
            }

            return parsedItems;
        } catch (JsonRpcRequestParseException e) {
            // Rethrow exceptions related to overall structure (e.g., empty array)
            throw e;
        } catch (JsonProcessingException e) {
            // Handle errors parsing the root JSON structure (not object or array, or invalid JSON)
            logger.warn("Failed to parse overall JSON-RPC request body: {}", requestBody, e);
            final JsonRpcError error = JsonRpcError.parseError("Invalid JSON received: " + e.getMessage());
            throw new JsonRpcRequestParseException(e, JsonRpcResponse.ofError(error, null), null);
        } catch (Exception e) {
            // Catch any other unexpected errors during parsing phase
            logger.error("Unexpected error during JSON-RPC request parsing: {}", requestBody, e);
            final JsonRpcError error = JsonRpcError.internalError(
                    "Unexpected server error during request parsing");
            throw new JsonRpcRequestParseException(e, JsonRpcResponse.ofError(error, null), null);
        }
    }

    /**
     * Parses a single JSON node into a {@link ParsedRpcItem}.
     * If parsing or validation fails, returns a {@link ParsedRpcItem} containing the error response.
     *
     * @param itemNode The JSON node representing a potential JSON-RPC request.
     * @return A {@link ParsedRpcItem} with either the parsed request or an error response.
     */
    private ParsedRpcItem parseSingleNode(JsonNode itemNode) {
        if (!itemNode.isObject()) {
            final JsonRpcError error = JsonRpcError.invalidRequest("Request item must be a JSON object.");
            // ID is unknown since it's not an object
            return new ParsedRpcItem(JsonRpcResponse.ofError(error, null));
        }

        Object idForError = null;
        final JsonNode idNode = itemNode.get("id"); // Attempt to get ID early for errors
        if (idNode != null) {
            if (idNode.isTextual()) {
                idForError = idNode.asText();
            } else if (idNode.isNumber()) {
                idForError = idNode.numberValue();
            } else if (idNode.isNull()) {
                idForError = null;
            }
            // Ignore other types for ID for error reporting
        }

        try {
            final JsonRpcRequest rpcRequest = mapper.treeToValue(itemNode, JsonRpcRequest.class);
            // Update idForError with the potentially more precise value from deserialization if needed
            idForError = rpcRequest.id();

            // Basic JSON-RPC 2.0 Validation
            if (!"2.0".equals(rpcRequest.jsonrpc())) {
                throw new IllegalArgumentException("Invalid JSON-RPC version: " + rpcRequest.jsonrpc());
            }
            if (rpcRequest.method() == null || rpcRequest.method().isEmpty()) {
                throw new IllegalArgumentException("JSON-RPC request 'method' is missing or empty");
            }
            // Parameter structure validation (must be object or array if present)
            if (rpcRequest.params() != null && !rpcRequest.hasObjectParams() && !rpcRequest.hasArrayParams()) {
                throw new IllegalArgumentException("JSON-RPC request 'params' must be an object or an array, " +
                                                   "but was: " + rpcRequest.params().getNodeType());
            }

            logger.debug("Parsed JSON-RPC request: method={}, id={}", rpcRequest.method(), idForError);
            return new ParsedRpcItem(rpcRequest);
        } catch (JsonProcessingException e) {
            // Distinguish between structural issues (Invalid Request)
            // and pure JSON syntax issues (Parse Error)
            // MismatchedInputException often indicates missing required fields for JsonRpcRequest.
            logger.warn("Failed to parse JSON node into JsonRpcRequest (id: {}): {}", idForError, itemNode, e);
            final JsonRpcError error;
            if (e instanceof MismatchedInputException) {
                // Likely a valid JSON object but missing required RPC fields.
                error = JsonRpcError.invalidRequest("Invalid request object: " + e.getMessage());
            } else {
                // Other JSON processing issues (e.g., invalid syntax within the node)
                error = JsonRpcError.parseError("Failed to parse request object: " + e.getMessage());
            }
            return new ParsedRpcItem(JsonRpcResponse.ofError(error, idForError));
        } catch (IllegalArgumentException e) {
            // Catches validation errors thrown above (e.g., wrong version, invalid params type)
            logger.warn("Invalid JSON-RPC request structure (id: {}): {}", idForError, itemNode, e);
            final JsonRpcError error =
                    JsonRpcError.invalidRequest("Invalid request object: " + e.getMessage());
            return new ParsedRpcItem(JsonRpcResponse.ofError(error, idForError));
        } catch (Exception e) {
            // Catch any other unexpected errors during single node processing
            logger.error("Unexpected error parsing JSON-RPC node (id: {}): {}",
                         idForError, itemNode.toString(), e);
            final JsonRpcError error = JsonRpcError.internalError(
                    "Unexpected server error parsing request item");
            return new ParsedRpcItem(JsonRpcResponse.ofError(error, idForError));
        }
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

        HttpService result = cachedRoutes.get(prefix);
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

    /**
     * Processes the response (or throwable) from the delegate service call and converts it
     * into the final JSON-RPC {@link HttpResponse}. This method handles both successful delegate
     * responses and errors occurring during delegate execution or response aggregation.
     *
     * @param delegatedAggregatedResponse The aggregated response from the delegate service. Can be {@code null}
     *                                    if {@code throwable} is not {@code null}.
     * @param throwable                   A throwable caught during delegate execution or response aggregation.
     *                                    Can be {@code null}.
     * @param methodName                  The name of the JSON-RPC method being processed. Used for logging and
     *                                    error messages.
     * @param requestId                   The ID from the original JSON-RPC request. Used for inclusion in the
     *                                    response.
     * @return The final {@link HttpResponse} containing the serialized JSON-RPC response (either success or
     *         error).
     */
    private HttpResponse processResponse(@Nullable AggregatedHttpResponse delegatedAggregatedResponse,
                                         @Nullable Throwable throwable,
                                         String methodName, @Nullable Object requestId) {
        try {
            JsonRpcResponse rpcResponse;

            if (throwable != null) {
                logger.warn("Error executing delegate service or aggregating response for JSON-RPC method " +
                            "'{}' (id: {}): {}", methodName, requestId, throwable.getMessage(), throwable);
                final JsonRpcError error = mapThrowableToJsonRpcError(throwable, methodName, requestId);
                rpcResponse = JsonRpcResponse.ofError(error, requestId);
            } else {
                assert delegatedAggregatedResponse != null
                        : "delegatedAggregatedResponse cannot be null if throwable is null";

                final HttpStatus status = delegatedAggregatedResponse.status();
                final String content = delegatedAggregatedResponse.contentUtf8();
                logger.debug("Received delegate response for JSON-RPC method '{}' (id: {}): status={}, " +
                             "content='{}'",
                             methodName, requestId, status,
                             content.length() > 500 ? content.substring(0, 500) + "..." : content);

                if (status.isSuccess()) {
                    // Parse the content as JsonNode before creating the response
                    try {
                        final JsonNode resultNode = mapper.readTree(content);
                        rpcResponse = JsonRpcResponse.ofSuccess(resultNode, requestId);
                    } catch (JsonProcessingException e) {
                        logger.warn("Failed to parse delegate response content as JSON for method '{}' " +
                                    "(id: {}). Treating as internal error. Content: {}",
                                    methodName, requestId, content, e);
                        final JsonRpcError error = JsonRpcError.internalError(
                                "Failed to parse successful delegate response as JSON");
                        rpcResponse = JsonRpcResponse.ofError(error, requestId);
                    }
                } else {
                    final JsonRpcError error = mapHttpResponseToJsonRpcError(delegatedAggregatedResponse,
                                                                               methodName, requestId);
                    rpcResponse = JsonRpcResponse.ofError(error, requestId);
                }
            }

            return createSyncHttpResponse(rpcResponse, requestId);
        } catch (Exception e) {
            logger.error("Unexpected error handling delegate response for method '{}' (id: {}): {}",
                         methodName, requestId, e.getMessage(), e);
            final JsonRpcError error = JsonRpcError.internalError(
                    "Unexpected server error handling delegate response");
            final JsonRpcResponse rpcResponse = JsonRpcResponse.ofError(error, requestId);
            return createSyncHttpResponse(rpcResponse, requestId);
        }
    }

    /**
     * Creates the final {@link HttpResponse} containing the serialized JSON-RPC response.
     * This method is used for both successful responses and errors that occur *after* the initial parsing phase
     * It always returns HTTP {@link HttpStatus#OK}, with the JSON-RPC error details included in the body
     * if the {@code rpcResponse} represents an error.
     *
     * @param rpcResponse The {@link JsonRpcResponse} to serialize (can be success or error).
     * @param requestId The original request ID (used for logging in case of serialization error).
     * @return An {@link HttpResponse} with status OK and JSON content type, containing the serialized response.
     *         Returns an {@link HttpStatus#INTERNAL_SERVER_ERROR} response if serialization fails.
     */
    private HttpResponse createSyncHttpResponse(JsonRpcResponse rpcResponse, @Nullable Object requestId) {
        try {
            final String responseBody = mapper.writeValueAsString(rpcResponse);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, responseBody);
        } catch (JsonProcessingException jsonProcessingException) {
            logger.error("CRITICAL: Failed to serialize final JSON-RPC response for request id {}: {}",
                         requestId, jsonProcessingException.getMessage(), jsonProcessingException);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                                   "Internal Server Error: Failed to serialize JSON-RPC response.");
        }
    }

    /**
     * Maps an error {@link AggregatedHttpResponse}
     * from the delegate service to a specific {@link JsonRpcError}.
     * Tries to interpret common HTTP error statuses (404, 400) into standard JSON-RPC errors.
     *
     * @param aggregatedHttpResponse The error response received from the delegate service.
     * @param methodName The name of the JSON-RPC method being processed.
     * @param requestId The ID of the original JSON-RPC request.
     * @return A {@link JsonRpcError} representing the delegate's error.
     */
    private JsonRpcError mapHttpResponseToJsonRpcError(AggregatedHttpResponse aggregatedHttpResponse,
                                                         String methodName, @Nullable Object requestId) {
        final HttpStatus status = aggregatedHttpResponse.status();
        final String content = aggregatedHttpResponse.contentUtf8();

        logger.warn("Mapping HTTP error response from delegate for method '{}' (id: {}): status={}, " +
                    "content='{}'",
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
                           "(delegate returned " + status + ")";
            if (!content.isEmpty()) {
                message += ": " + content;
            }
            return JsonRpcError.invalidRequest(message);
        } else {
            String message = "Internal server error during delegate execution for method '" +
                             methodName + "' " + "(delegate returned " + status + ')';
            if (!content.isEmpty()) {
                message += ": " + content;
            }
            return JsonRpcError.internalError(message);
        }
    }

    /**
     * Maps a {@link Throwable} (potentially from delegate execution or response aggregation) to a specific
     * {@link JsonRpcError}.
     * Unwraps {@link java.util.concurrent.CompletionException} if present and maps common exception types.
     *
     * @param exception   The exception caught during processing.
     * @param methodName  The name of the JSON-RPC method being processed.
     * @param requestId   The ID of the original JSON-RPC request.
     * @return A {@link JsonRpcError} representing the exception.
     */
    private JsonRpcError mapThrowableToJsonRpcError(Throwable exception, String methodName,
                                                  @Nullable Object requestId) {
        Throwable cause = exception;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }

        logger.warn("Mapping throwable for method '{}' (id: {}): {}: {}",
                    methodName, requestId, cause.getClass().getName(), cause.getMessage(), cause);

        if (cause instanceof JsonRpcRequestParseException) {
            final JsonRpcResponse errorResponse = ((JsonRpcRequestParseException) cause).getErrorResponse();
            final JsonRpcError specificError = errorResponse.error();
            if (specificError != null) {
                return specificError;
            } else {
                logger.error("Inconsistency: JsonRpcRequestParseException (for method '{}', id: {}) " +
                             "contained a JsonRpcResponse without an error object.",
                             methodName, requestId);
                return JsonRpcError.internalError(
                        "Internal server error processing request validation failure for method '" +
                        methodName + "'");
            }
        } else if (cause instanceof JsonProcessingException) {
            return JsonRpcError.internalError(
                    "Internal Error: Failed to process delegate response as JSON. Cause: " +
                    cause.getMessage());
        } else if (cause instanceof IllegalArgumentException) {
            return JsonRpcError.invalidRequest(
                    "Invalid argument or state encountered during processing: " + cause.getMessage());
        } else {
            // Catch-all for other unexpected exceptions during delegate processing
            return JsonRpcError.internalError(
                    "Internal server error during delegate processing for method '" + methodName + "': " +
                    cause.getMessage());
        }
    }

    /**
     * Creates a {@link CompletableFuture}{@code <HttpResponse>}
     * containing the serialized JSON-RPC error response.
     * This is specifically used for errors detected during the initial request parsing/validation phase
     * (i.e., caught via {@link JsonRpcRequestParseException}).
     *
     * @param rpcResponse The {@link JsonRpcResponse} containing the error details (must be an error response).
     * @param requestId   The ID from the original request (if available, {@code null} otherwise).
     * @return A completed future containing the {@link HttpResponse} with status OK and the serialized
     *         JSON-RPC error.
     *         Returns a future with an {@link HttpStatus#INTERNAL_SERVER_ERROR} response if serialization fails
     */
    private CompletableFuture<HttpResponse> createErrorHttpResponseFuture(JsonRpcResponse rpcResponse,
                                                                        @Nullable Object requestId) {
        try {
            final HttpResponse errorHttpResponse = HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                                   mapper.writeValueAsString(rpcResponse));
            // Use UnmodifiableFuture here
            return UnmodifiableFuture.completedFuture(errorHttpResponse);
        } catch (JsonProcessingException jsonProcessingException) {
            logger.error("Failed to serialize JSON-RPC error response for request id {}: {}",
                         requestId, jsonProcessingException.getMessage(), jsonProcessingException);
            final HttpResponse internalErrorResponse =
                    HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                    MediaType.PLAIN_TEXT_UTF_8,
                                    "Internal Server Error: " +
                                                "Failed to serialize JSON-RPC error response.");
            // Use UnmodifiableFuture here
            return UnmodifiableFuture.completedFuture(internalErrorResponse);
        }
    }
}
