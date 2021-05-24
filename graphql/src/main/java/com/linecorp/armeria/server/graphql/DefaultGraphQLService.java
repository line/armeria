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

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.dataloader.DataLoaderRegistry;
import org.reactivestreams.Publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.internal.server.JacksonUtil;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.ExecutionInput;
import graphql.ExecutionInput.Builder;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQL;
import graphql.GraphqlErrorException;
import graphql.com.google.common.collect.ImmutableMap;

final class DefaultGraphQLService extends AbstractHttpService implements GraphQLService {

    private static final ObjectMapper OBJECT_MAPPER = JacksonUtil.newDefaultObjectMapper();

    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    private final GraphQL graphQL;

    private final DataLoaderRegistry dataLoaderRegistry;

    private final boolean useBlockingTaskExecutor;

    DefaultGraphQLService(GraphQL graphQL) {
        this(graphQL, new DataLoaderRegistry());
    }

    DefaultGraphQLService(GraphQL graphQL, DataLoaderRegistry dataLoaderRegistry) {
        this(graphQL, dataLoaderRegistry, false);
    }

    DefaultGraphQLService(GraphQL graphQL, DataLoaderRegistry dataLoaderRegistry,
                          boolean useBlockingTaskExecutor) {
        this.graphQL = requireNonNull(graphQL, "graphQL");
        this.dataLoaderRegistry = requireNonNull(dataLoaderRegistry, "dataLoaderRegistry");
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest request) {
        final QueryParams queryString = QueryParams.fromQueryString(ctx.query());
        final String query = queryString.get("query");
        if (query == null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST,
                                   MediaType.PLAIN_TEXT,
                                   "Query is required");
        }
        final Map<String, Object> variables = toVariableMap(queryString.get("variables"));
        final String operationName = queryString.get("operationName");
        return execute(ctx, executionInput(query, ctx, variables, operationName));
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest request) {
        final MediaType contentType = request.contentType();
        if (contentType == null) {
            return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                   MediaType.PLAIN_TEXT,
                                   "Could not process GraphQL request");
        }
        if (MediaType.isJson(contentType)) {
            return HttpResponse.from(request.aggregate().handle((req, thrown) -> {
                if (thrown != null) {
                    return HttpResponse.ofFailure(thrown);
                }
                final String body = req.contentUtf8();
                if (Strings.isNullOrEmpty(body)) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, "Body is required");
                }
                final Map<String, Object> requestMap = parseJsonString(body);
                final String query = (String) requestMap.getOrDefault("query", "");
                return execute(ctx, executionInput(query, ctx,
                                                   toVariableMap(requestMap.get("variables")),
                                                   (String) requestMap.get("operationName")));
            }));
        }
        if (contentType.is(MediaType.GRAPHQL)) {
            return HttpResponse.from(request.aggregate().handle((req, thrown) -> {
                if (thrown != null) {
                    return HttpResponse.ofFailure(thrown);
                }
                final String query = req.contentUtf8();
                return execute(ctx, executionInput(query, ctx, null, null));
            }));
        }
        return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                               MediaType.PLAIN_TEXT,
                               "Could not process GraphQL request");
    }

    private ExecutionInput executionInput(String query, ServiceRequestContext ctx,
                                          @Nullable Map<String, Object> variables,
                                          @Nullable String operationName) {
        final Builder builder = ExecutionInput.newExecutionInput(query);
        if (variables != null && !variables.isEmpty()) {
            builder.variables(variables);
        }
        if (operationName != null) {
            builder.operationName(operationName);
        }
        if (dataLoaderRegistry != null) {
            builder.dataLoaderRegistry(dataLoaderRegistry);
        }
        return builder.context(ctx).build();
    }

    private HttpResponse execute(ServiceRequestContext ctx, ExecutionInput input) {
        if (useBlockingTaskExecutor) {
            final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
            ctx.blockingTaskExecutor().execute(() -> {
                final ExecutionResult executionResult = graphQL.execute(input);
                future.complete(HttpResponse.of(MediaType.JSON_UTF_8,
                                                toJsonString(executionResult.toSpecification())));
            });
            return HttpResponse.from(future);
        } else {
            return HttpResponse.from(graphQL.executeAsync(input).handle((executionResult, cause) -> {
                if (cause != null) {
                    final ExecutionResult error = toExecutionResult(cause);
                    return HttpResponse.of(MediaType.JSON_UTF_8, toJsonString(error.toSpecification()));
                }

            // TODO: When WebSocket is implemented, it should be removed.
            if (executionResult.getData() instanceof Publisher) {
                final ExecutionResult error =
                        toExecutionResult(new UnsupportedOperationException("WebSocket is not implemented"));
                return HttpResponse.of(MediaType.JSON_UTF_8, toJsonString(error.toSpecification()));
            }

                return HttpResponse.of(MediaType.JSON_UTF_8, toJsonString(executionResult.toSpecification()));
            }));
        }
    }

    private static ExecutionResult toExecutionResult(Throwable cause) {
        return new ExecutionResultImpl(GraphqlErrorException.newErrorException()
                                                            .message(cause.getMessage())
                                                            .cause(cause)
                                                            .build());
    }

    private static Map<String, Object> toVariableMap(@Nullable Object variables) {
        if (variables == null) {
            return ImmutableMap.of();
        }

        if (variables instanceof Map) {
            final Map<?, ?> variablesMap = (Map<?, ?>) variables;
            final ImmutableMap.Builder<String, Object> builder =
                    ImmutableMap.builderWithExpectedSize(variablesMap.size());
            variablesMap.forEach((k, v) -> builder.put(String.valueOf(k), v));
            return builder.build();
        } else {
            throw new IllegalArgumentException("Unknown parameter type variables");
        }
    }

    private static Map<String, Object> toVariableMap(@Nullable String value) {
        if (Strings.isNullOrEmpty(value)) {
            return ImmutableMap.of();
        }
        return parseJsonString(value);
    }

    private static Map<String, Object> parseJsonString(String content) {
        try {
            return OBJECT_MAPPER.readValue(content, JSON_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to parse a JSON document: " + content, e);
        }
    }

    private static String toJsonString(Map<String, Object> result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to write a JSON document: " + result, e);
        }
    }
}
