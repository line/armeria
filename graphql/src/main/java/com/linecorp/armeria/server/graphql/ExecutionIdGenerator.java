/*
 * Copyright 2023 LINE Corporation
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

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.GraphQLContext;
import graphql.execution.ExecutionId;
import graphql.execution.ExecutionIdProvider;

/**
 * Generates a unique execution ID of each GraphQL request.
 */
@UnstableApi
@FunctionalInterface
public interface ExecutionIdGenerator {
    /**
     * Returns the default {@link ExecutionIdGenerator} that uses {@link ServiceRequestContext#id()}
     * as the execution ID.
     */
    static ExecutionIdGenerator of() {
        return (requestContext, query, operationName, graphqlContext) -> ExecutionId.from(
                requestContext.id().text());
    }

    /**
     * Generates an execution ID based on the provided context, query, operation name, and graphql context.
     */
    ExecutionId generate(ServiceRequestContext requestContext, String query, String operationName,
                         GraphQLContext graphqlContext);

    /**
     * Returns an {@link ExecutionIdProvider} that uses this {@link ExecutionIdGenerator} to generate
     * execution IDs.
     */
    default ExecutionIdProvider asExecutionProvider() {
        return (query, operationName, context) -> {
            final GraphQLContext graphqlContext = (GraphQLContext) context;
            final ServiceRequestContext requestContext = GraphqlServiceContexts.get(graphqlContext);
            return generate(requestContext, query, operationName, graphqlContext);
        };
    }
}
