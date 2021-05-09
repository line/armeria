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

import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(DefaultGraphQLService.class);

    private static final ObjectMapper OBJECT_MAPPER = JacksonUtil.newDefaultObjectMapper();

    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    private final GraphQL graphQL;

    @Nullable
    private final DataLoaderRegistry dataLoaderRegistry;

    DefaultGraphQLService(GraphQL graphQL, @Nullable DataLoaderRegistry dataLoaderRegistry) {
        this.graphQL = graphQL;
        this.dataLoaderRegistry = dataLoaderRegistry;
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
        final Map<String, Object> variables = Optional.ofNullable(queryString.get("variables"))
                                                      .filter(Strings::isNullOrEmpty)
                                                      .map(DefaultGraphQLService::toVariableMap)
                                                      .orElse(ImmutableMap.of());
        final String operationName = queryString.get("operationName");
        return execute(executionInput(query, ctx, variables, operationName));
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest request) {
        final MediaType contentType = request.contentType();
        if (contentType == null) {
            return HttpResponse.of(HttpStatus.UNPROCESSABLE_ENTITY,
                                   MediaType.PLAIN_TEXT,
                                   "Could not process GraphQL request");
        }
        if (contentType.is(MediaType.JSON) || contentType.subtype().endsWith("+json")) {
            return HttpResponse.from(request.aggregate().handleAsync((req, thrown) -> {
                if (thrown != null) {
                    logger.warn("{} Failed to aggregate a request:", ctx, thrown);
                    return HttpResponse.ofFailure(thrown);
                }
                final String body = req.contentUtf8();
                if (Strings.isNullOrEmpty(body)) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT, "Body is required");
                }
                final Map<String, Object> requestMap = parseJsonString(body);
                final String query = (String) requestMap.getOrDefault("query", "");
                return execute(executionInput(query, ctx,
                                              toVariableMap(requestMap.get("variables")),
                                              (String) requestMap.get("operationName")));
            }));
        }
        if (contentType.is(MediaType.GRAPHQL)) {
            return HttpResponse.from(request.aggregate().handleAsync((req, thrown) -> {
                if (thrown != null) {
                    logger.warn("{} Failed to aggregate a request:", ctx, thrown);
                    return HttpResponse.ofFailure(thrown);
                }
                final String query = req.contentUtf8();
                return execute(executionInput(query, ctx, null, null));
            }));
        }
        return HttpResponse.of(HttpStatus.UNPROCESSABLE_ENTITY,
                               MediaType.PLAIN_TEXT,
                               "Could not process GraphQL request");
    }

    private ExecutionInput executionInput(String query, ServiceRequestContext ctx,
                                          @Nullable Map<String, Object> variables,
                                          @Nullable String operationName) {
        final Builder builder = ExecutionInput.newExecutionInput(query);
        if (variables != null) {
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

    private HttpResponse execute(ExecutionInput input) {
        final ExecutionResult executionResult = executionResult(input);
        return HttpResponse.of(MediaType.JSON_UTF_8, toJsonString(executionResult.toSpecification()));
    }

    private ExecutionResult executionResult(ExecutionInput input) {
        try {
            return graphQL.execute(input);
        } catch (RuntimeException e) {
            return new ExecutionResultImpl(GraphqlErrorException.newErrorException()
                                                                .message(e.getMessage())
                                                                .cause(e)
                                                                .build());
        }
    }

    private static Map<String, Object> toVariableMap(@Nullable Object variables) {
        if (variables == null) {
            return ImmutableMap.of();
        }

        if (variables instanceof Map) {
            final ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
            final Map<?, ?> variablesMap = (Map<?, ?>) variables;
            variablesMap.forEach((k, v) -> builder.put(String.valueOf(k), v));
            return builder.build();
        } else {
            return toVariableMap(String.valueOf(variables));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toVariableMap(String value) {
        if (Strings.isNullOrEmpty(value)) {
            return ImmutableMap.of();
        }
        return parseJsonString(value);
    }

    private static Map<String, Object> parseJsonString(String content) {
        try {
            return OBJECT_MAPPER.readValue(content, JSON_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to parse a JSON document: " + e, e);
        }
    }

    private static String toJsonString(Map<String, Object> result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to write a JSON document: " + e, e);
        }
    }
}
