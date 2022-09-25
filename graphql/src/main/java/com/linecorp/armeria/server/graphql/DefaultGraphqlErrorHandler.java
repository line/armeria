package com.linecorp.armeria.server.graphql;

import static com.linecorp.armeria.server.graphql.DefaultGraphqlService.newExecutionResult;

import javax.annotation.Nonnull;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.validation.ValidationError;
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

enum DefaultGraphqlErrorHandler implements GraphqlErrorHandler {
    INSTANCE;

    @Nonnull
    @Override
    public HttpResponse handle(
            ServiceRequestContext ctx,
            ExecutionInput input,
            ExecutionResult result,
            MediaType negotiatedProduceType,
            @Nullable Throwable cause) {
        if (cause != null) {
            // graphQL.executeAsync() returns an error in the executionResult with getErrors().
            // Use 500 Internal Server Error because this cause might be unexpected.
            final ExecutionResult error = newExecutionResult(cause);
            return HttpResponse.ofJson(
                    HttpStatus.INTERNAL_SERVER_ERROR, negotiatedProduceType, error.toSpecification());
        }

        if (result.getErrors().stream().anyMatch(ValidationError.class::isInstance)) {
            return HttpResponse.ofJson(HttpStatus.BAD_REQUEST, negotiatedProduceType, result.toSpecification());
        }

        return HttpResponse.ofJson(negotiatedProduceType, result.toSpecification());
    }
}
