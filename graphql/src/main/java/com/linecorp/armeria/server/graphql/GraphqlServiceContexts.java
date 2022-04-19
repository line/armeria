/*
 * Copyright 2022 LINE Corporation
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

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.GraphQLContext;
import graphql.com.google.common.collect.ImmutableMap;
import graphql.schema.DataFetchingEnvironment;

/**
 * Utilities for accessing {@link ServiceRequestContext} in a GraphQL environment.
 */
@UnstableApi
public final class GraphqlServiceContexts {

    private static final String GRAPHQL_CONTEXT_KEY = "com.linecorp.armeria.graphql.context.key";

    /**
     * Returns a {@link Map} containing the {@link ServiceRequestContext}.
     */
    public static Map<String, Object> graphqlContext(ServiceRequestContext requestContext) {
        requireNonNull(requestContext, "requestContext");
        return ImmutableMap.of(GRAPHQL_CONTEXT_KEY, requestContext);
    }

    /**
     * Returns {@link ServiceRequestContext} in {@link GraphQLContext}.
     * For example:
     * <pre>{@code
     * new DataFetcher<>() {
     *     @Override
     *     public String get(DataFetchingEnvironment env) throws Exception {
     *         final ServiceRequestContext ctx = GraphqlUtil.serviceRequestContext(env.getGraphQlContext());
     *         // ...
     *     }
     * };
     * }</pre>
     *
     * @throws IllegalStateException if the specified {@link GraphQLContext} doesn't contain
     *                               a {@link ServiceRequestContext}.
     */
    public static ServiceRequestContext serviceRequestContext(GraphQLContext graphQLContext) {
        requireNonNull(graphQLContext, "graphQLContext");
        final ServiceRequestContext ctx = graphQLContext.get(GRAPHQL_CONTEXT_KEY);
        if (ctx == null) {
            throw new IllegalStateException("missing request context");
        }
        return ctx;
    }

    /**
     * Returns {@link ServiceRequestContext} in {@link DataFetchingEnvironment}.
     * For example:
     * <pre>{@code
     * new DataFetcher<>() {
     *     @Override
     *     public String get(DataFetchingEnvironment env) throws Exception {
     *         final ServiceRequestContext ctx = GraphqlUtil.serviceRequestContext(env);
     *         // ...
     *     }
     * };
     * }</pre>
     *
     * @throws IllegalStateException if the specified {@link DataFetchingEnvironment} doesn't contain
     *                               a {@link ServiceRequestContext}.
     */
    public static ServiceRequestContext serviceRequestContext(DataFetchingEnvironment environment) {
        requireNonNull(environment, "environment");
        return serviceRequestContext(environment.getGraphQlContext());
    }

    private GraphqlServiceContexts() {}
}
