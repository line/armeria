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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQLError;

/**
 * A handler that maps GraphQL errors to an {@link HttpResponse}.
 */
@FunctionalInterface
public interface GraphqlErrorsHandler {

    GraphqlErrorsHandler defaultGraphqlErrorsHandler
            = GraphqlErrorsHandlers.of(GraphqlErrorsMappingFunction.of());

    /**
     * Returns the default {@link GraphqlErrorsHandler}.
     */
    static GraphqlErrorsHandler of() {
        return defaultGraphqlErrorsHandler;
    }

    /**
     * Returns a {@link GraphqlErrorsHandler} with the {@link GraphqlErrorsMappingFunction} that returns a
     * different {@link HttpStatus} depending on the errors raised in the {@link GraphqlService}.
     * @param errorsMappingFunction the function which maps the {@link GraphQLError} to an {@link HttpStatus}.
     */
    static GraphqlErrorsHandler of(GraphqlErrorsMappingFunction errorsMappingFunction) {
        requireNonNull(errorsMappingFunction, "errorsMappingFunction");
        return GraphqlErrorsHandlers.of(errorsMappingFunction);
    }

    /**
     * Maps the GraphQL {@link ExecutionResult} to the {@link HttpResponse}.
     */
    @Nullable
    HttpResponse handle(ServiceRequestContext ctx, ExecutionInput input, MediaType produceType,
                        ExecutionResult executionResult, @Nullable Throwable cause);

    /**
     * Returns a composed {@link GraphqlErrorsHandler} that applies this first and the specified
     * other later if this returns null.
     */
    default GraphqlErrorsHandler orElse(GraphqlErrorsHandler other) {
        requireNonNull(other, "other");
        if (this == other) {
            return this;
        }
        return (ctx, input, produceType, executionResult, cause) -> {
            final HttpResponse response = handle(ctx, input, produceType, executionResult, cause);
            if (response != null) {
                return response;
            }
            return other.handle(ctx, input, produceType, executionResult, cause);
        };
    }
}
