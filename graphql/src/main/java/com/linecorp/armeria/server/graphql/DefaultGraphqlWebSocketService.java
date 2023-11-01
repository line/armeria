/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.graphql.protocol.GraphqlRequest;
import com.linecorp.armeria.common.multipart.MultipartFile;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.internal.server.FileAggregatedMultipart;
import com.linecorp.armeria.internal.server.graphql.protocol.GraphqlUtil;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.IWebSocketService;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.server.websocket.WebSocketServiceHandler;
import com.linecorp.armeria.server.graphql.protocol.MultipartVariableMapper;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.ExecutionId;
import org.dataloader.DataLoaderRegistry;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.isHttp1WebSocketUpgradeRequest;
import static com.linecorp.armeria.internal.common.websocket.WebSocketUtil.isHttp2WebSocketUpgradeRequest;
import static java.util.Objects.requireNonNull;

public class DefaultGraphqlWebSocketService extends AbstractHttpService implements GraphqlService, IWebSocketService, WebSocketServiceHandler, GraphqlExecutor {
    private static final Logger logger = LoggerFactory.getLogger(DefaultGraphqlWebSocketService.class);

    private final GraphQL graphQL;

    private final Function<? super ServiceRequestContext,
        ? extends DataLoaderRegistry> dataLoaderRegistryFunction;

    private final boolean useBlockingTaskExecutor;

    private final GraphqlErrorHandler errorHandler;

    private final WebSocketService webSocketService;

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> JSON_MAP =
        new TypeReference<Map<String, Object>>() {};

    private static final TypeReference<Map<String, List<String>>> MAP_PARAM =
        new TypeReference<Map<String, List<String>>>() {};

    DefaultGraphqlWebSocketService(
        GraphQL graphQL,
        Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFunction,
        boolean useBlockingTaskExecutor, GraphqlErrorHandler errorHandler) {
        this.graphQL = requireNonNull(graphQL, "graphQL");
        this.dataLoaderRegistryFunction = requireNonNull(dataLoaderRegistryFunction,
            "dataLoaderRegistryFunction");
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        this.errorHandler = errorHandler;

        this.webSocketService = WebSocketService.builder(this)
            .subprotocols("graphql-transport-ws")
            .build();
    }

    @Override
    protected HttpResponse doConnect(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        logger.debug("Received a GraphQL CONNECT request: {}", req);
        if (ctx.sessionProtocol().isExplicitHttp2() && isHttp2WebSocketUpgradeRequest(req.headers())) {
            return webSocketService.serve(ctx, req);
        }
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        logger.debug("Received a GET request: {}", req);
        if (ctx.sessionProtocol().isExplicitHttp1() && isHttp1WebSocketUpgradeRequest(req.headers())) {
            logger.debug("Received a GraphQL GET request with a WebSocket upgrade headers: {}", req.headers());
            return webSocketService.serve(ctx, req);
        }

        logger.debug("Received a GraphQL GET headers: {}", req.headers());
        return doGetNotWebSocket(ctx, req);
    }

    private HttpResponse doGetNotWebSocket(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final QueryParams queryString = QueryParams.fromQueryString(ctx.query());
        String query = queryString.get("query");
        if (Strings.isNullOrEmpty(query)) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, "query is missing");
        }
        query = query.trim();
        if (query.startsWith("mutation")) {
            // GET requests MUST NOT be used for executing mutation operations.
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED, MediaType.PLAIN_TEXT,
                "Mutation is not allowed");
        }

        final String operationName = queryString.get("operationName");
        final Map<String, Object> variables;
        final Map<String, Object> extensions;
        try {
            variables = toMap(queryString.get("variables"));
            extensions = toMap(queryString.get("extensions"));
        } catch (JsonProcessingException ex) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                "Failed to parse a GraphQL query: " + ctx.query());
        }

        return executeGraphqlViaHttp(ctx, GraphqlRequest.of(query, operationName, variables, extensions));
    }

    protected HttpResponse executeGraphqlViaHttp(ServiceRequestContext ctx, GraphqlRequest req) throws Exception {
        final MediaType produceType = GraphqlUtil.produceType(ctx.request().headers());
        if (produceType == null) {
            return HttpResponse.of(HttpStatus.NOT_ACCEPTABLE, MediaType.PLAIN_TEXT,
                "Only %s and %s compatible media types are acceptable",
                MediaType.GRAPHQL_RESPONSE_JSON, MediaType.JSON);
        }

        final ExecutionInput.Builder builder = ExecutionInput.newExecutionInput(req.query());
        final Map<String, Object> variables = req.variables();
        if (!variables.isEmpty()) {
            builder.variables(variables);
        }

        final Map<String, Object> extensions = req.extensions();
        if (!extensions.isEmpty()) {
            builder.extensions(extensions);
        }

        final String operationName = req.operationName();
        if (operationName != null) {
            builder.operationName(operationName);
        }

        final ExecutionInput executionInput =
            builder.context(ctx)
                .graphQLContext(GraphqlServiceContexts.graphqlContext(ctx))
                .dataLoaderRegistry(dataLoaderRegistryFunction.apply(ctx))
                .build();
        return execute(ctx, executionInput, produceType);
    }

    private HttpResponse execute(
        ServiceRequestContext ctx, ExecutionInput input, MediaType produceType) {
        final CompletableFuture<ExecutionResult> future;
        if (useBlockingTaskExecutor) {
            future = CompletableFuture.supplyAsync(() -> graphQL.execute(input), ctx.blockingTaskExecutor());
        } else {
            future = graphQL.executeAsync(input);
        }
        return HttpResponse.of(
            future.handle((executionResult, cause) -> {
                if (executionResult.getData() instanceof Publisher) {
                    logger.warn("executionResult.getData() returns a {} but we are not in a web socket",
                        executionResult.getData().toString());

                    return HttpResponse.ofJson(HttpStatus.NOT_IMPLEMENTED,
                        produceType,
                        toSpecification("Please use websocket to subscribe"));
                }

                if (executionResult.getErrors().isEmpty() && cause == null) {
                    return HttpResponse.ofJson(produceType, executionResult.toSpecification());
                }

                return errorHandler.handle(ctx, input, executionResult, cause);
            }));
    }

    static Map<String, Object> toSpecification(String message) {
        requireNonNull(message, "message");
        final Map<String, Object> error = ImmutableMap.of("message", message);
        return ImmutableMap.of("errors", ImmutableList.of(error));
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest request) throws Exception {
        logger.debug("Received a GraphQL POST request: {}", request);
        final MediaType contentType = request.contentType();
        if (contentType == null) {
            return unsupportedMediaType();
        }

        // See https://github.com/jaydenseric/graphql-multipart-request-spec/blob/master/readme.md
        if (contentType.is(MediaType.MULTIPART_FORM_DATA)) {
            return HttpResponse.of(FileAggregatedMultipart.aggregateMultipart(ctx, request)
                .thenApply(multipart -> {
                    try {
                        final ListMultimap<String, String> multipartParams = multipart.params();
                        final String operationsParam = getValueFromMultipartParam("operations", multipartParams);
                        final String mapParam = getValueFromMultipartParam("map", multipartParams);

                        final Map<String, Object> operations = parseJsonString(operationsParam, JSON_MAP);
                        final Map<String, List<String>> map = parseJsonString(mapParam, MAP_PARAM);
                        final String query = toStringFromJson("query", operations.get("query"));
                        if (Strings.isNullOrEmpty(query)) {
                            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                "query is missing: " + operationsParam);
                        }
                        final Map<String, Object> variables = toMapFromJson(operations.get("variables"));
                        final Map<String, Object> copiedVariables = new HashMap<>(variables);
                        final Map<String, Object> extensions = toMapFromJson(operations.get("extensions"));
                        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
                            final List<MultipartFile> multipartFiles = multipart.files().get(entry.getKey());
                            bindMultipartVariable(copiedVariables, entry.getValue(), multipartFiles);
                        }

                        return executeGraphqlViaHttp(ctx, GraphqlRequest.of(query, null, copiedVariables, extensions));
                    } catch (JsonProcessingException ex) {
                        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                            "Failed to parse a JSON document: " + ex.getMessage());
                    } catch (IllegalArgumentException ex) {
                        return createResponse(ex);
                    } catch (Exception ex) {
                        return HttpResponse.ofFailure(ex);
                    }
                }));
        }

        if (contentType.isJson()) {
            return HttpResponse.of(request.aggregate(ctx.eventLoop()).thenApply(req -> {
                try (SafeCloseable ignored = ctx.push()) {
                    final String body = req.contentUtf8();
                    if (Strings.isNullOrEmpty(body)) {
                        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                            "Missing request body");
                    }

                    try {
                        final Map<String, Object> requestMap = parseJsonString(body, JSON_MAP);
                        final String query = toStringFromJson("query", requestMap.get("query"));
                        if (Strings.isNullOrEmpty(query)) {
                            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                "query is missing");
                        }

                        final String operationName =
                            toStringFromJson("operationName", requestMap.get("operationName"));
                        final Map<String, Object> variables = toMapFromJson(requestMap.get("variables"));
                        final Map<String, Object> extensions = toMapFromJson(requestMap.get("extensions"));

                        return executeGraphqlViaHttp(ctx, GraphqlRequest.of(query, operationName,
                            variables, extensions));
                    } catch (JsonProcessingException ex) {
                        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                            "Failed to parse a JSON document: " + body);
                    } catch (IllegalArgumentException ex) {
                        return createResponse(ex);
                    } catch (Exception ex) {
                        return HttpResponse.ofFailure(ex);
                    }
                }
            }));
        }

        if (contentType.is(MediaType.GRAPHQL)) {
            return HttpResponse.of(request.aggregate(ctx.eventLoop()).thenApply(req -> {
                try (SafeCloseable ignored = ctx.push()) {
                    final String query = req.contentUtf8();
                    if (Strings.isNullOrEmpty(query)) {
                        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                            "query is missing");
                    }

                    try {
                        return executeGraphqlViaHttp(ctx, GraphqlRequest.of(query));
                    } catch (Exception ex) {
                        return HttpResponse.ofFailure(ex);
                    }
                }
            }));
        }

        return unsupportedMediaType();
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        // Response stream will be supported via WebSocket.
        final MediaType contentType = routingContext.contentType();
        if (contentType != null && contentType.is(MediaType.MULTIPART_FORM_DATA)) {
            return ExchangeType.REQUEST_STREAMING;
        }
        return ExchangeType.UNARY;
    }

    @Override
    public WebSocket handle(ServiceRequestContext ctx, WebSocket in) {
        logger.debug("Handling websocket");
        WebSocketWriter outgoing = WebSocket.streaming();
        GraphqlWSSubProtocol protocol = new GraphqlWSSubProtocol(this);

        in.subscribe(new GraphqlWebSocketSubscriber(protocol, outgoing));

        return outgoing;
    }

    @Override
    public ExecutionResult executeGraphql(ExecutionInput.Builder executionInput) {
        logger.debug("Executing graphql");
        // TODO dataloaderregistry needs context somehow?
        try {
            return graphQL.execute(executionInput
                .executionId(ExecutionId.generate()));
        } catch (Exception e) {
            logger.error("Execution failed", e);
            throw e;
        }
    }

    private static Map<String, Object> toMap(@Nullable String value) throws JsonProcessingException {
        if (Strings.isNullOrEmpty(value)) {
            return ImmutableMap.of();
        }
        return parseJsonString(value, JSON_MAP);
    }

    @Nullable
    private static String toStringFromJson(String name, @Nullable Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String) {
            return (String) value;
        } else {
            throw new IllegalArgumentException("Invalid " + name + " (expected: string)");
        }
    }

    /**
     * This only works reliably if maybeMap is from Json, as maps(objects) in Json
     * can only have string keys.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMapFromJson(@Nullable Object maybeMap) {
        if (maybeMap == null) {
            return ImmutableMap.of();
        }

        if (maybeMap instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) maybeMap;
            if (map.isEmpty()) {
                return ImmutableMap.of();
            }
            return Collections.unmodifiableMap((Map<String, Object>) maybeMap);
        } else {
            throw new IllegalArgumentException("Unknown parameter type variables");
        }
    }

    private static <T> T parseJsonString(String content, TypeReference<T> typeReference)
        throws JsonProcessingException {
        return mapper.readValue(content, typeReference);
    }

    private static String getValueFromMultipartParam(String name, ListMultimap<String, String> params) {
        final List<String> list = params.get(name);
        if (list.size() > 1) {
            throw new IllegalArgumentException("More than one '" + name + "' received.");
        }
        if (list.isEmpty() || Strings.isNullOrEmpty(list.get(0))) {
            throw new IllegalArgumentException('\'' + name + "' form field is missing");
        }
        return list.get(0);
    }

    private static HttpResponse unsupportedMediaType() {
        return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            MediaType.PLAIN_TEXT,
            "Unsupported media type. Only JSON compatible types and " +
                "application/graphql are supported.");
    }

    private static void bindMultipartVariable(Map<String, Object> variables, List<String> operationsPaths,
                                              @Nullable List<MultipartFile> multipartFiles) {
        if (multipartFiles == null || multipartFiles.isEmpty()) {
            return;
        }
        final MultipartFile multipartFile = multipartFiles.get(0);
        for (String objectPath : operationsPaths) {
            MultipartVariableMapper.mapVariable(objectPath, variables, multipartFile);
        }
    }

    private static HttpResponse createResponse(IllegalArgumentException ex) {
        final String message = ex.getMessage() == null ? HttpStatus.BAD_REQUEST.reasonPhrase()
            : ex.getMessage();
        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, message);
    }
}



