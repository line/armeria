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

import javax.annotation.Nullable;

import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        final String variablesString = queryString.get("variables");
        final Map<String, Object> variables = variablesString != null ?
                                              parseJsonString(variablesString) : null;
        final String operationName = queryString.get("operationName");
        final String extensionsString = queryString.get("extensions");
        final Map<String, Object> extensions = extensionsString != null ?
                                               parseJsonString(extensionsString) : null;
        return execute(executionInput(query, ctx, variables, operationName, extensions));
    }

    @SuppressWarnings("unchecked")
    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest request) {
        final MediaType contentType = request.contentType();
        if (contentType != null && contentType.is(MediaType.JSON)) {
            return HttpResponse.from(request.aggregate().handleAsync((req, thrown) -> {
                if (thrown != null) {
                    logger.warn("{} Failed to aggregate a request:", ctx, thrown);
                    return HttpResponse.ofFailure(thrown);
                }
                final Map<String, Object> inputMap = parseJsonString(req.contentUtf8());
                if (inputMap.isEmpty()) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST,
                                           MediaType.PLAIN_TEXT,
                                           "Body is required");
                }
                final String query = (String) inputMap.getOrDefault("query", "");
                return execute(executionInput(query, ctx,
                                              (Map<String, Object>) inputMap.get("variables"),
                                              (String) inputMap.get("operationName"),
                                              (Map<String, Object>) inputMap.get("extensions")));
            }));
        } else if (contentType != null &&
                   contentType.type().equals(MediaType.GRAPHQL.type()) &&
                   contentType.subtype().equals(MediaType.GRAPHQL.subtype())) {
            return HttpResponse.from(request.aggregate().handleAsync((req, thrown) -> {
                if (thrown != null) {
                    logger.warn("{} Failed to aggregate a request:", ctx, thrown);
                    return HttpResponse.ofFailure(thrown);
                }
                final String query = req.contentUtf8();
                return execute(executionInput(query, ctx, null, null, null));
            }));
        } else {
            return HttpResponse.of(HttpStatus.UNPROCESSABLE_ENTITY,
                                   MediaType.PLAIN_TEXT,
                                   "Could not process GraphQL request");
        }
    }

    private ExecutionInput executionInput(String query, ServiceRequestContext ctx,
                                          @Nullable Map<String, Object> variables,
                                          @Nullable String operationName,
                                          @Nullable Map<String, Object> extensions) {
        final Builder builder = ExecutionInput.newExecutionInput(query);
        if (variables != null) {
            builder.variables(variables);
        }
        if (operationName != null) {
            builder.operationName(operationName);
        }
        if (extensions != null) {
            builder.extensions(extensions);
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

    private static Map<String, Object> parseJsonString(@Nullable String content) {
        if (content == null) {
            return ImmutableMap.of();
        }
        try {
            return OBJECT_MAPPER.readValue(content, JSON_MAP);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing object to JSON: " + e.getMessage(), e);
        }
    }

    private static String toJsonString(Map<String, Object> result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error deserializing object to JSON: " + e.getMessage(), e);
        }
    }
}
