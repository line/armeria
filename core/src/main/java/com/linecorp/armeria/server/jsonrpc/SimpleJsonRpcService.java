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

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
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
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Default implementation of {@link JsonRpcService}.
 * This service handles the parsing of incoming JSON-RPC requests (both single and batch),
 * routes them to the appropriate delegate {@link HttpService} based on the JSON-RPC method and configured path
 * prefixes, and then aggregates and formats the responses back into JSON-RPC format.
 * Delegate services can be either non-annotated {@link HttpService}s or annotated service objects,
 * typically registered via {@link JsonRpcServiceBuilder}.
 */
@UnstableApi
public class SimpleJsonRpcService implements JsonRpcService {

    private static final Logger logger = LoggerFactory.getLogger(SimpleJsonRpcService.class);

    private final Set<Route> routes;
    private final ObjectMapper mapper;
    private final Map<String, HttpService> cachedServices = new HashMap<>();

    /**
     * Creates a new instance of {@link SimpleJsonRpcService}.
     *
     * @param routes The set of base {@link Route}s that this service will handle. Must not be {@code null}.
     * @param mapper The Jackson {@link ObjectMapper} to be used for all JSON processing.
     *               Must not be {@code null}.
     * @param cachedServices An optional map of pre-populated services, typically containing non-annotated
     *                       {@link HttpService}s that were added directly via
     *                       {@link JsonRpcServiceBuilder#addService(String, HttpService)}.
     *                       If {@code null}, an empty cache is initialized.
     */
    public SimpleJsonRpcService(Set<Route> routes,
            ObjectMapper mapper,
            @Nullable Map<String, HttpService> cachedServices) {
        this.routes = routes;
        this.mapper = mapper;
        if (cachedServices != null) {
            this.cachedServices.putAll(cachedServices);
        }
    }

    /**
     * {@inheritDoc}
     * Returns the set of {@link Route}s this service is configured to handle.
     */
    @Override
    public Set<Route> routes() {
        return routes;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method aggregates the incoming {@link HttpRequest} and then delegates the core JSON-RPC
     * processing logic
     * to {@link #handleAggregatedRequest(ServiceRequestContext, HttpRequest, AggregatedHttpRequest)}.</p>
     * @return An {@link HttpResponse} future that will eventually contain the JSON-RPC response(s).
     */
    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.of(req.aggregate()
                .thenCompose(aggregatedRequest -> handleAggregatedRequest(ctx, req, aggregatedRequest)));
    }

    /**
     * Handles the fully aggregated HTTP request.
     * This method orchestrates the parsing of the JSON-RPC request(s), dispatches them for processing,
     * and then assembles the final HTTP response.
     *
     * @param ctx The {@link ServiceRequestContext} of the current request.
     * @param originalRequest The original, non-aggregated {@link HttpRequest}.
     * @param aggregatedRequest The {@link AggregatedHttpRequest} containing the full request body.
     * @return A {@link CompletableFuture} that will complete with the final {@link HttpResponse}.
     */
    private CompletableFuture<HttpResponse> handleAggregatedRequest(
            ServiceRequestContext ctx, HttpRequest originalRequest, AggregatedHttpRequest aggregatedRequest) {

        final List<JsonRpcItemParseResult> parsedItems =
                JsonRpcRequestParser.parseRequest(aggregatedRequest);
        final List<CompletableFuture<AggregatedHttpResponse>> futures =
                processJsonRpcRequest(ctx, originalRequest, parsedItems);

        if (futures.isEmpty()) {
            // This can happen if the request was a batch containing only notifications,
            // or an empty valid batch.
            // The case of an empty JSON array `[]` resulting in an error is handled by JsonRpcRequestParser.
            // If JsonRpcRequestParser returns an empty list
            // (e.g. after filtering out only notifications from a batch),
            return UnmodifiableFuture.completedFuture(HttpResponse.of(HttpStatus.OK));
        }

        // If the original request was not a JSON array but resulted in a single future
        // (e.g., a successful single non-notification request or a parse error for a single request),
        // return the single response directly without wrapping it in a JSON array.
        if (parsedItems.size() == 1 && !isOriginalRequestJsonArray(aggregatedRequest)) {
            return futures.get(0).thenApply(AggregatedHttpResponse::toHttpResponse);
        }

        // Batch request (multiple items or a single item originally wrapped in a JSON array, e.g., "[{...}]")
        // Combine all individual response futures into a single HttpResponse containing a JSON array.
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> combineResponses(futures));
    }

    /**
     * Combines a list of {@link CompletableFuture}s, each representing an individual JSON-RPC response
     * (or error) within a batch, into a single {@link HttpResponse} containing a JSON array of these responses.
     *
     * <p>If an individual future completes exceptionally
     * or if its content cannot be parsed into a {@link JsonNode},
     * a JSON-RPC internal error node is substituted for that item in the resulting batch array.
     * If the final serialization of the entire batch array fails, a single top-level JSON-RPC internal error
     * response is returned.
     * </p>
     *
     * @param futures A list of {@link CompletableFuture}s, where each future is expected to yield an
     *                {@link AggregatedHttpResponse} for a single JSON-RPC item.
     * @return An {@link HttpResponse} containing a JSON array of all processed responses from the batch,
     *         or a single JSON-RPC error response if the final batch serialization fails.
     */
    private HttpResponse combineResponses(List<CompletableFuture<AggregatedHttpResponse>> futures) {
        final List<JsonNode> responseNodes =
                futures.stream()
                        .map(future -> {
                            try {
                                final AggregatedHttpResponse aggResp = future.join();
                                return mapper.readTree(aggResp.contentUtf8());
                            } catch (Exception e) {
                                logger.warn("Failed to aggregate/parse individual JSON-RPC " +
                                        "response within batch," +
                                        "returning internal error node", e);

                                final JsonRpcError internalError =
                                        JsonRpcError.INTERNAL_ERROR.withData("Failed to process response");
                                final JsonRpcResponse errorResponse =
                                        JsonRpcResponse.ofError(internalError, null);
                                return mapper.valueToTree(errorResponse);
                            }
                        }).collect(Collectors.toList());
        try {
            return HttpResponse.ofJson(MediaType.JSON_UTF_8, responseNodes);
        } catch (Exception e) { // Catch potential exceptions from ofJson
            logger.error("Failed to serialize final batch JSON-RPC response", e);
            // This is a server-side issue, respond with a single internal error
            final JsonRpcResponse errResp = JsonRpcResponse.ofError(
                    JsonRpcError.INTERNAL_ERROR.withData("Failed to create batch response"), null);

            return JsonRpcResponseFactory.toHttpResponse(errResp, mapper, null);
        }
    }

    /**
     * Processes a list of {@link JsonRpcItemParseResult}s, which are the outcome of parsing the initial
     * HTTP request body.
     *
     * <p>For each item:
     * <ul>
     *     <li>If the item represents a parsing error ({@link JsonRpcItemParseResult#isError()} is true),
     *         an {@link AggregatedHttpResponse} future for that error is added to the list of futures.</li>
     *     <li>If the item is a valid {@link JsonRpcRequest}:</li>
     *     <ul>
     *         <li>If it's a notification ({@link JsonRpcRequest#notificationRequest()} is true), it is routed
     *             via {@link #route(ServiceRequestContext, HttpRequest, JsonRpcRequest)}. Notifications do not
     *             produce a response, so no future is added to the list. Errors during the routing or
     *             initiation of a notification are logged.</li>
     *         <li>If it's a regular (non-notification) request, it is routed via
     *             {@link #route(ServiceRequestContext, HttpRequest, JsonRpcRequest)}, and the resulting
     *             {@link HttpResponse} (aggregated) future is added to the list.</li>
     *     </ul>
     *     <li>Exceptions occurring during the routing or processing of a regular request (e.g.,
     *         {@link JsonRpcServiceNotFoundException}) are caught and converted into appropriate
     *         JSON-RPC error response futures.</li>
     * </ul>
     * </p>
     *
     * @param ctx The {@link ServiceRequestContext} of the current request.
     * @param originalRequest The original, non-aggregated {@link HttpRequest}.
     * @param parsedItems A list of {@link JsonRpcItemParseResult}s obtained from parsing the request body.
     * @return A list of {@link CompletableFuture}s, each eventually yielding an {@link AggregatedHttpResponse}.
     *         This list will only contain futures for non-notification requests or for parsing errors.
     *         It will be empty if {@code parsedItems} contained only notifications or was empty.
     */
    private List<CompletableFuture<AggregatedHttpResponse>> processJsonRpcRequest(
            ServiceRequestContext ctx, HttpRequest originalRequest, List<JsonRpcItemParseResult> parsedItems) {

        final List<CompletableFuture<AggregatedHttpResponse>> futures = new LinkedList<>();

        for (JsonRpcItemParseResult item : parsedItems) {
            if (item.isError()) {
                final JsonRpcResponse errorResponse = item.errorResponse();
                assert errorResponse != null;

                futures.add(
                        JsonRpcResponseFactory.toHttpResponse(errorResponse, mapper, errorResponse.id())
                                .aggregate());
            } else {
                final JsonRpcRequest rpcRequest = item.request();
                assert rpcRequest != null;

                if (rpcRequest.notificationRequest()) {
                    // Handle notification request
                    try {
                        route(ctx, originalRequest, rpcRequest);
                    } catch (Exception e) {
                        logger.warn("Failed to route or start execution for notification method " +
                                "'{}': {}", rpcRequest.method(), e.getMessage(), e);
                    }
                } else {
                    // Handle regular request
                    try {
                        final HttpResponse delegateResponseFuture = route(ctx, originalRequest, rpcRequest);

                        futures.add(
                                delegateResponseFuture.aggregate());
                    } catch (JsonRpcServiceNotFoundException e) {
                        final JsonRpcResponse errorResponse = JsonRpcResponse.ofError(
                                JsonRpcError.METHOD_NOT_FOUND.withData(e.getMessage()), rpcRequest.id());

                        futures.add(
                                JsonRpcResponseFactory.toHttpResponse(errorResponse, mapper, rpcRequest.id())
                                        .aggregate());
                    } catch (Exception e) {
                        final JsonRpcResponse response =
                                JsonRpcResponseFactory.fromThrowable(e, rpcRequest.id(), rpcRequest.method());

                        futures.add(
                                JsonRpcResponseFactory.toHttpResponse(response, mapper, rpcRequest.id())
                                        .aggregate());
                    }
                }
            }
        }
        return futures;
    }

    /**
     * Routes a validated {@link JsonRpcRequest} to the appropriate delegate {@link HttpService}.
     *
     * <p>This method performs the following steps:
     * <ol>
     *   <li>Constructs the {@code targetPath} by combining the base path from the current
     *       {@link ServiceRequestContext#mappedPath()} (the prefix) and the {@link JsonRpcRequest#method()}.
     *       For example, if mappedPath is "/user" and method is "getDetails",
     *       targetPath becomes "/user/getDetails".</li>
     *   <li>Finds the delegate {@link HttpService} responsible for this {@code targetPath} using
     *       {@link #findService(ServiceRequestContext, String)}.</li>
     *   <li>Creates new {@link RequestHeaders} for the delegate request, ensuring the path in the headers
     *       reflects the {@code targetPath}.</li>
     *   <li>Serializes the {@link JsonRpcRequest#params()} (if present) into a JSON string to form the body
     *       of the delegate {@link HttpRequest}. If params are null, an empty body is used.</li>
     *   <li>Creates a new {@link ServiceRequestContext} specifically for the delegate service invocation.
     *       This new context is populated with {@link JsonRpcAttributes} (ID, method, notification status)
     *       from the {@code jsonRpcRequest}, which might be used by decorators like
     *       {@link JsonRpcServiceDecorator}.</li>
     *   <li>Invokes the {@code serve()} method of the {@code targetService} with the newly created context
     *       and delegate request.</li>
     * </ol>
     *
     * @param context The {@link ServiceRequestContext} of the original incoming request.
     * @param originalHttpRequest The original {@link HttpRequest} (used to copy headers for the delegate).
     * @param jsonRpcRequest The parsed and validated {@link JsonRpcRequest} to be routed.
     * @return An {@link HttpResponse} produced by the delegate {@link HttpService}.
     * @throws JsonRpcServiceNotFoundException
     *         if no delegate service can be found for the constructed target path.
     * @throws Exception if an error occurs during parameter serialization or service invocation.
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

        final ServiceRequestContext newContext = ServiceRequestContext.of(delegateRequest);
        newContext.setAttr(JsonRpcAttributes.ID, jsonRpcRequest.id());
        newContext.setAttr(JsonRpcAttributes.METHOD, jsonRpcRequest.method());
        newContext.setAttr(JsonRpcAttributes.IS_NOTIFICATION, jsonRpcRequest.notificationRequest());

        // Serve using a new context derived from the delegate request.
        return targetService.serve(newContext, delegateRequest);
    }

    /**
     * Finds the appropriate {@link HttpService} to handle a request based on the target path,
     * which is typically formed by combining a service prefix and a JSON-RPC method name.
     * This method uses an internal cache ({@link #cachedServices}) for efficiency.
     *
     * <p>Lookup logic:
     * <ol>
     *   <li>The {@code targetPath} is normalized to ensure it starts with a '/'.</li>
     *   <li>The cache is checked first using the full {@code targetPath}. If not found, it's checked using
     *       the original prefix from {@link ServiceRequestContext#mappedPath()} (this primarily serves
     *       non-annotated services added via {@link JsonRpcServiceBuilder#addService(String, HttpService)}
     *       which might be registered with just a prefix).</li>
     *   <li>If the service is not found in the cache, the server's configuration
     *       ({@link ServiceConfig#server()}) is queried to find a service whose route pattern string
     *       exactly matches the {@code targetPath}. This lookup is for services (often annotated)
     *       that are registered with more specific paths.</li>
     *   <li>If a service is found via server configuration, it is added to the {@link #cachedServices}
     *       using the {@code targetPath} as the key and then returned.</li>
     *   <li>If no service is found after checking both the cache and the server configuration,
     *       a {@link JsonRpcServiceNotFoundException} is thrown.</li>
     * </ol>
     *
     * @param context The {@link ServiceRequestContext}, used primarily to access the server configuration
     *                ({@link ServiceRequestContext#config()}) and the original mapped path.
     * @param targetPath The target path (e.g., "/userService/getUser" or "/math/subtract") for which
     *                   to find the responsible {@link HttpService}.
     * @return The {@link HttpService} registered to handle requests for the {@code targetPath}.
     * @throws JsonRpcServiceNotFoundException if no {@link HttpService} is found for the specified
     *                                         {@code targetPath}.
     */
    private HttpService findService(ServiceRequestContext context, String targetPath)
            throws JsonRpcServiceNotFoundException {
        // Use the targetPath directly for cache lookup and server config search.
        final String lookupPath = targetPath.startsWith("/") ? targetPath : '/' + targetPath;
        final String prefix = context.mappedPath();

        HttpService result = cachedServices.get(lookupPath) != null ? cachedServices.get(lookupPath)
                : cachedServices.get(prefix);
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

            cachedServices.put(lookupPath, result);
            logger.debug("Cached service for path: {}", lookupPath);
        } else {
            logger.trace("Using cached service for path: {}", lookupPath);
        }
        return result;
    }

    /**
     * Utility method to check if the content of an {@link AggregatedHttpRequest} appears to be a JSON array.
     * It performs a simple string check on the trimmed UTF-8 content of the request body.
     *
     * @param aggregatedRequest The {@link AggregatedHttpRequest} whose content is to be checked.
     * @return {@code true} if the request body string starts with '[' and ends with ']',
     *         {@code false} otherwise.
     */
    private boolean isOriginalRequestJsonArray(AggregatedHttpRequest aggregatedRequest) {
        final String requestBody = aggregatedRequest.contentUtf8().trim();
        return requestBody.startsWith("[") && requestBody.endsWith("]");
    }
}
