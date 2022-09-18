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

import java.util.List;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorException;

final class GraphqlErrorsHandlers {

    private static final Logger logger = LoggerFactory.getLogger(GraphqlErrorsHandlers.class);

    private GraphqlErrorsHandlers() {}

    /**
     * Returns a {@link HttpResponse} based on {@link Throwable} or List of {@link GraphQLError}.
     * @param errorsMappingFunction The function which maps the {@link GraphQLError} to an {@link HttpStatus}
     */
    static GraphqlErrorsHandler of(GraphqlErrorsMappingFunction errorsMappingFunction) {
        final GraphqlErrorsMappingFunction mappingFunction = withDefault(errorsMappingFunction);
        return (ctx, input, produceType, executionResult, cause) -> {
            if (cause != null) {
                // graphQL.executeAsync() returns an error in the executionResult with getErrors().
                // Use 500 Internal Server Error because this cause might be unexpected.
                final ExecutionResult error = newExecutionResult(cause);
                return HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR, produceType,
                                           error.toSpecification());
            }
            final List<GraphQLError> errors = executionResult.getErrors();

            final HttpStatus httpStatus = mappingFunction.apply(ctx, input, errors);
            assert httpStatus != null; // withDefault
            return toHttpResponse(httpStatus, executionResult, produceType);
        };
    }

    /**
     * Return {@link HttpResponse} based {@link HttpStatus}, {@link ExecutionResult}, {@link MediaType}.
     */
    private static HttpResponse toHttpResponse(HttpStatus httpStatus, ExecutionResult executionResult,
                                               MediaType produceType) {
        // TODO: When WebSocket is implemented, it should be removed.
        if (executionResult.getData() instanceof Publisher) {
            logger.warn("executionResult.getData() returns a {} that is not supported yet.",
                        executionResult.getData().toString());
            final ExecutionResult error =
                    newExecutionResult(new UnsupportedOperationException("WebSocket is not implemented"));
            return HttpResponse.ofJson(HttpStatus.NOT_IMPLEMENTED, produceType, error.toSpecification());
        }
        return HttpResponse.ofJson(httpStatus, produceType, executionResult.toSpecification());
    }

    /**
     * Return {@link ExecutionResult} based {@link Throwable}.
     */
    private static ExecutionResult newExecutionResult(Throwable cause) {
        return new ExecutionResultImpl(GraphqlErrorException.newErrorException()
                                                            .message(cause.getMessage())
                                                            .cause(cause)
                                                            .build());
    }

    /**
     * Ensure that GraphqlErrorMappingFunction never returns null by falling back to the default.
     */
    private static GraphqlErrorsMappingFunction withDefault(
            GraphqlErrorsMappingFunction errorsMappingFunction) {
        requireNonNull(errorsMappingFunction, "errorsMappingFunction");
        if (errorsMappingFunction == GraphqlErrorsMappingFunction.of()) {
            return errorsMappingFunction;
        }
        return errorsMappingFunction.orElse(GraphqlErrorsMappingFunction.of());
    }
}
