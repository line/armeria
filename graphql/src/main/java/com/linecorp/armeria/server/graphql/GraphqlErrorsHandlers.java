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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphqlErrorException;
import graphql.validation.ValidationError;

final class GraphqlErrorsHandlers {

    static final GraphqlErrorsHandler defaultErrorsHandler =
            (ctx, input, result, negotiatedProduceType, cause) -> {
                if (cause != null) {
                    // graphQL.executeAsync() returns an error in the executionResult with getErrors().
                    // Use 500 Internal Server Error because this cause might be unexpected.
                    final ExecutionResult error = newExecutionResult(cause);
                    return HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR, negotiatedProduceType,
                                               error.toSpecification());
                }

                if (result.getErrors().stream().anyMatch(ValidationError.class::isInstance)) {
                    return HttpResponse.ofJson(
                            HttpStatus.BAD_REQUEST, negotiatedProduceType, result.toSpecification());
                }

                return HttpResponse.ofJson(negotiatedProduceType, result.toSpecification());
            };

    /**
     * Return {@link ExecutionResult} based {@link Throwable}.
     */
    static ExecutionResult newExecutionResult(Throwable cause) {
        return new ExecutionResultImpl(GraphqlErrorException.newErrorException()
                                                            .message(cause.getMessage())
                                                            .cause(cause)
                                                            .build());
    }

    private GraphqlErrorsHandlers() {}
}
