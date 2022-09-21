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

enum DefaultGraphqlErrorHandler implements GraphqlErrorHandler {
    INSTANCE;

    @Nonnull
    @Override
    public HttpResponse handle(
            ServiceRequestContext ctx,
            ExecutionInput input,
            ExecutionResult result,
            MediaType negotiatedProduceType,
            @Nullable Throwable cause
    ) {
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
