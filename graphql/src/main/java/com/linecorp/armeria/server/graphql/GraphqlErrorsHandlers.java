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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorException;
import graphql.validation.ValidationError;

final class GraphqlErrorsHandlers {

    private static final Logger logger = LoggerFactory.getLogger(GraphqlErrorsHandlers.class);

    private static final GraphqlErrorsHandler defaultErrorsHandler =
            (ctx, input, result, negotiatedProduceType, cause) -> {
                if (cause != null) {
                    // graphQL.executeAsync() returns an error in the executionResult with getErrors().
                    // Use 500 Internal Server Error because this cause might be unexpected.
                    final ExecutionResult error = newExecutionResult(cause);
                    return HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR, negotiatedProduceType,
                                               error.toSpecification());
                }
                final List<GraphQLError> errors = result.getErrors();
                final HttpStatus httpStatus = graphqlErrorsToHttpStatus(errors);
                return toHttpResponse(httpStatus, result, negotiatedProduceType);
            };

    static GraphqlErrorsHandler of() {
        return defaultErrorsHandler;
    }

    private GraphqlErrorsHandlers() {}

    /**
     * Return {@link ExecutionResult} based {@link Throwable}.
     */
    static ExecutionResult newExecutionResult(Throwable cause) {
        return new ExecutionResultImpl(GraphqlErrorException.newErrorException()
                                                            .message(cause.getMessage())
                                                            .cause(cause)
                                                            .build());
    }

    /**
     * Return an {@link HttpStatus} based on the specified list of {@link GraphQLError}s.
     */
    private static HttpStatus graphqlErrorsToHttpStatus(List<GraphQLError> errors) {
        if (errors.isEmpty()) {
            return HttpStatus.OK;
        }
        if (errors.stream().anyMatch(ValidationError.class::isInstance)) {
            // The server SHOULD deny execution with a status code of 400 Bad Request for
            // invalidate documentation.
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.SERVICE_UNAVAILABLE;
    }

    /**
     * Return {@link HttpResponse} based {@link HttpStatus}, {@link ExecutionResult}, {@link MediaType}.
     */
    private static HttpResponse toHttpResponse(HttpStatus httpStatus, ExecutionResult executionResult,
                                               MediaType produceType) {
        return HttpResponse.ofJson(httpStatus, produceType, executionResult.toSpecification());
    }
}
