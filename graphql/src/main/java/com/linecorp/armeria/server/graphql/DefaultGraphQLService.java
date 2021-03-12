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
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.ExecutionResultImpl;
import graphql.GraphQL;
import graphql.GraphqlErrorException;
import graphql.com.google.common.collect.ImmutableMap;

class DefaultGraphQLService extends AbstractHttpService implements GraphQLService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultGraphQLService.class);

    private static final MediaType APPLICATION_GRAPHQL = MediaType.create("application", "graphql");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    @Nullable
    private final DataLoaderRegistry dataLoaderRegistry;

    private final Route route;

    private final GraphQLExecutor executor;

    DefaultGraphQLService(GraphQL graphQL, @Nullable DataLoaderRegistry dataLoaderRegistry, Route route,
                          @Nullable Function<? super GraphQLExecutor, ? extends GraphQLExecutor> delegate) {
        this.dataLoaderRegistry = dataLoaderRegistry;
        this.route = requireNonNull(route, "route");
        final Function<? super GraphQLExecutor, ? extends GraphQLExecutor> decorator =
                delegate == null ? Function.identity() : delegate;
        executor = decorator.apply(new InternalGraphQLExecutor(requireNonNull(graphQL, "graphQL")));
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest request) throws Exception {
        final QueryParams queryString = QueryParams.fromQueryString(ctx.query());
        final String query = queryString.get("query");
        if (query == null) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST,
                                   MediaType.PLAIN_TEXT,
                                   "Query is required");
        }
        final String variablesString = queryString.get("variables");
        final Map<String, Object> variables = variablesString != null ?
                                              serialize(variablesString) : ImmutableMap.of();
        final String operationName = queryString.get("operationName");
        final String extensionsString = queryString.get("extensions");
        final Map<String, Object> extensions = extensionsString != null ?
                                               serialize(extensionsString) : ImmutableMap.of();
        return execute(ctx, GraphQLInput.builder(query, ctx)
                                        .variables(variables)
                                        .operationName(operationName)
                                        .extensions(extensions)
                                        .dataLoaderRegistry(dataLoaderRegistry)
                                        .build());
    }

    @SuppressWarnings("unchecked")
    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest request) throws Exception {
        final MediaType contentType = request.contentType();
        if (contentType != null && contentType.type().equals(MediaType.JSON.type()) &&
            (contentType.subtype().equals(MediaType.JSON.subtype()) ||
             contentType.subtype().endsWith("+json"))) {
            return HttpResponse.from(request.aggregate().handleAsync((req, thrown) -> {
                if (thrown != null) {
                    logger.warn("{} Failed to aggregate a request:", ctx, thrown);
                    return HttpResponse.ofFailure(thrown);
                }
                final Map<String, Object> inputMap = serialize(req.contentUtf8());
                if (inputMap.isEmpty()) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST,
                                           MediaType.PLAIN_TEXT,
                                           "Body is required");
                }
                final String query = (String) inputMap.getOrDefault("query", "");
                final Map<String, Object> variables =
                        (Map<String, Object>) inputMap.getOrDefault("variables", ImmutableMap.of());
                final Map<String, Object> extensions =
                        (Map<String, Object>) inputMap.getOrDefault("extensions", ImmutableMap.of());
                return execute(ctx, GraphQLInput.builder(query, ctx)
                                                .variables(variables)
                                                .operationName((String) inputMap.get("operationName"))
                                                .extensions(extensions)
                                                .dataLoaderRegistry(dataLoaderRegistry)
                                                .build());
            }));
        } else if (contentType != null &&
                   contentType.type().equals(APPLICATION_GRAPHQL.type()) &&
                   contentType.subtype().equals(APPLICATION_GRAPHQL.subtype())) {
            return HttpResponse.from(request.aggregate().handleAsync((req, thrown) -> {
                if (thrown != null) {
                    logger.warn("{} Failed to aggregate a request:", ctx, thrown);
                    return HttpResponse.ofFailure(thrown);
                }
                final String query = req.contentUtf8();
                return execute(ctx, GraphQLInput.builder(query, ctx)
                                                .dataLoaderRegistry(dataLoaderRegistry)
                                                .build());
            }));
        } else {
            return HttpResponse.of(HttpStatus.UNPROCESSABLE_ENTITY,
                                   MediaType.PLAIN_TEXT,
                                   "Could not process GraphQL request");
        }
    }

    private HttpResponse execute(ServiceRequestContext ctx, GraphQLInput input) {
        try {
            final GraphQLOutput output = executor.serve(ctx, input);
            return HttpResponse.of(MediaType.JSON_UTF_8,
                                   deserialize(output.toSpecification()));
        } catch (Exception e) {
            final ExecutionResultImpl executionResult = new ExecutionResultImpl(
                    GraphqlErrorException.newErrorException()
                                         .message(e.getMessage())
                                         .cause(e)
                                         .build());
            final GraphQLOutput output = GraphQLOutput.of(executionResult);
            return HttpResponse.of(MediaType.JSON_UTF_8, deserialize(output.toSpecification()));
        }
    }

    @Override
    public Set<Route> routes() {
        return ImmutableSet.of(route);
    }

    private static Map<String, Object> serialize(@Nullable String content) {
        if (content == null) {
            return ImmutableMap.of();
        }
        try {
            return OBJECT_MAPPER.readValue(content, JSON_MAP);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing object to JSON: " + e.getMessage(), e);
        }
    }

    private static String deserialize(Map<String, Object> result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error deserializing object to JSON: " + e.getMessage(), e);
        }
    }

    private static final class InternalGraphQLExecutor implements GraphQLExecutor {

        private final GraphQL graphQL;

        private InternalGraphQLExecutor(GraphQL graphQL) {
            this.graphQL = graphQL;
        }

        @Override
        public GraphQLOutput serve(ServiceRequestContext ctx, GraphQLInput input) {
            return GraphQLOutput.of(graphQL.execute(input.toExecutionInput()));
        }
    }
}
