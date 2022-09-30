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

import javax.annotation.Nonnull;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.graphql.protocol.GraphqlUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.validation.ValidationError;

enum DefaultGraphqlErrorHandler implements GraphqlErrorHandler {
    INSTANCE;

    @Nonnull
    @Override
    public HttpResponse handle(
            ServiceRequestContext ctx,
            ExecutionInput input,
            ExecutionResult result,
            @Nullable Throwable cause) {

        final MediaType produceType = GraphqlUtil.produceType(ctx.request().headers());
        assert produceType != null; // Checked in DefaultGraphqlService#executeGraphql

        if (cause != null) {
            // graphQL.executeAsync() returns an error in the executionResult with getErrors().
            // Use 500 Internal Server Error because this cause might be unexpected.
            final Map<String, Object> specification;
            if (cause instanceof GraphQLError) {
                specification = ((GraphQLError) cause).toSpecification();
            } else {
                specification = toSpecification(cause);
            }
            return HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR, produceType, specification);
        }

        if (result.getErrors().stream().anyMatch(ValidationError.class::isInstance)) {
            return HttpResponse.ofJson(HttpStatus.BAD_REQUEST, produceType, result.toSpecification());
        }

        return HttpResponse.ofJson(produceType, result.toSpecification());
    }

    private static Map<String, Object> toSpecification(Throwable cause) {
        requireNonNull(cause, "cause");
        final String message;
        if (cause.getMessage() != null) {
            message = cause.getMessage();
        } else {
            message = cause.toString();
        }

        return DefaultGraphqlService.toSpecification(message);
    }
}
