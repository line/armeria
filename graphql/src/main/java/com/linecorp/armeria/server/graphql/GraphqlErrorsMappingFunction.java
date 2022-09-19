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

import java.util.List;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.ExecutionInput;
import graphql.GraphQLError;
import graphql.validation.ValidationError;

/**
 * A mapping function that converts a List of {@link GraphQLError} into an {@link HttpStatus}.
 */
@FunctionalInterface
public interface GraphqlErrorsMappingFunction {

    /**
     * Return default mapping function which checks null and {@link ValidationError}.
     */
    static GraphqlErrorsMappingFunction of() {
        return (ctx, input, errors) -> GraphqlErrorsHandlers.graphqlErrorsToHttpStatus(errors);
    }

    /**
     * Maps the specified List of {@link GraphQLError} to an {@link HttpStatus}.
     */
    @Nullable
    HttpStatus apply(ServiceRequestContext ctx, ExecutionInput input, List<GraphQLError> errors);

    /**
     * Returns a composed {@link GraphqlErrorsMappingFunction} that applies this first and the specified
     * other later if this returns null.
     */
    default GraphqlErrorsMappingFunction orElse(GraphqlErrorsMappingFunction other) {
        requireNonNull(other, "other");
        if (this == other) {
            return this;
        }
        return (ctx, input, errors) -> {
            final HttpStatus httpStatus = apply(ctx, input, errors);
            if (httpStatus != null) {
                return httpStatus;
            }
            return other.apply(ctx, input, errors);
        };
    }
}
