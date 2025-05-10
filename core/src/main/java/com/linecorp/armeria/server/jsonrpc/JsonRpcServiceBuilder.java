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

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.jsonrpc.JsonRpcError;
import com.linecorp.armeria.common.jsonrpc.JsonRpcRequest;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponse;
import com.linecorp.armeria.common.jsonrpc.JsonRpcResponseFactory;
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
                         .decorator(JsonRpcServiceDecorator::new);
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

        final List<JsonRpcItemParseResult> parsedItems =
                JsonRpcRequestParser.parseRequest(aggregatedRequest);
        final List<CompletableFuture<AggregatedHttpResponse>> futures = 
                processJsonRpcRequest(ctx, originalRequest, parsedItems);

        if (futures.isEmpty()) {
            // This happens if the batch contained ONLY notifications.
            // (Empty array case is handled by JsonRpcRequestParseException)
            return UnmodifiableFuture.completedFuture(HttpResponse.of(HttpStatus.OK));
        }

        // If the original request was not an array but resulted in a single future
        // (successful single request),
        // return the single response directly without wrapping in an array.
        if (parsedItems.size() == 1 && !isOriginalRequestJsonArray(aggregatedRequest)) {
            return futures.get(0).thenApply(AggregatedHttpResponse::toHttpResponse);
        }

        // Batch request or single request that was originally an array (e.g. "[{...}]")
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    final List<JsonNode> responseNodes = futures.stream()
                            .map(future -> {
                                try {
                                    final AggregatedHttpResponse aggResp = future.join();
                                    return mapper.readTree(aggResp.contentUtf8());
                                } catch (Exception e) {
                                    logger.warn("Failed to aggregate/parse individual JSON-RPC " +
                                                "response within batch," +
                                                "returning internal error node", e);

                                    final JsonRpcError internalError =
                                            JsonRpcError.internalError("Failed to process response");

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
                                JsonRpcError
                                        .internalError("Failed to create batch response"),
                                null
                        );

                        return JsonRpcResponseFactory.toHttpResponse(errResp, mapper, null);
                    }
                });
    }

    private List<CompletableFuture<AggregatedHttpResponse>> processJsonRpcRequest(
            ServiceRequestContext ctx, HttpRequest originalRequest, List<JsonRpcItemParseResult> parsedItems) {

        final List<CompletableFuture<AggregatedHttpResponse>> futures = new LinkedList<>();

        for (JsonRpcItemParseResult item : parsedItems) {
            if (item.isError()) {
                final JsonRpcResponse errorResponse = item.errorResponse();
                assert errorResponse != null;

                futures.add(
                        JsonRpcResponseFactory.toHttpResponse(errorResponse, mapper, errorResponse.id())
                                              .aggregate()
                );
            } else {
                final JsonRpcRequest rpcRequest = item.request();
                assert rpcRequest != null;

                if (rpcRequest.isNotification()) {
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
                                delegateResponseFuture.aggregate()
                        );
                    } catch (JsonRpcServiceNotFoundException e) {
                        final JsonRpcResponse errorResponse = JsonRpcResponse.ofError(
                                JsonRpcError.methodNotFound(e.getMessage()), rpcRequest.id());

                        futures.add(
                                JsonRpcResponseFactory.toHttpResponse(errorResponse, mapper, rpcRequest.id())
                                                      .aggregate()
                        );
                    } catch (Exception e) {
                        final JsonRpcResponse response =
                                JsonRpcResponseFactory.fromThrowable(e, rpcRequest.id(), rpcRequest.method());

                        futures.add(
                                JsonRpcResponseFactory.toHttpResponse(response, mapper, rpcRequest.id())
                                                      .aggregate()
                        );
                    }
                }
            }
        }
        return futures;
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

        final ServiceRequestContext newContext = ServiceRequestContext.of(delegateRequest);
        newContext.setAttr(JsonRpcAttributes.ID, jsonRpcRequest.id());
        newContext.setAttr(JsonRpcAttributes.METHOD, jsonRpcRequest.method());
        newContext.setAttr(JsonRpcAttributes.IS_NOTIFICATION, jsonRpcRequest.isNotification());

        // Serve using a new context derived from the delegate request.
        return targetService.serve(newContext, delegateRequest);
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

        HttpService result = cachedRoutes.get(lookupPath);
        if (result == null) {
            // Search server config using the exact lookupPath.
            result = context.config().server().serviceConfigs().stream()
                            .filter(serviceConfig ->
                                            serviceConfig.route().patternString().equals(lookupPath))
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

    private boolean isOriginalRequestJsonArray(AggregatedHttpRequest aggregatedRequest) {
        final String requestBody = aggregatedRequest.contentUtf8().trim();
        return requestBody.startsWith("[") && requestBody.endsWith("]");
    }
}
