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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.dataloader.DataLoaderRegistry;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.graphql.protocol.AbstractGraphqlService;
import com.linecorp.armeria.server.graphql.protocol.GraphqlRequest;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.GraphqlErrorException;
import graphql.validation.ValidationError;

final class DefaultGraphqlService extends AbstractGraphqlService implements GraphqlService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultGraphqlService.class);

    private final GraphQL graphQL;

    private final DataLoaderRegistry dataLoaderRegistry;

    private final boolean useBlockingTaskExecutor;

    DefaultGraphqlService(GraphQL graphQL, DataLoaderRegistry dataLoaderRegistry,
                          boolean useBlockingTaskExecutor) {
        this.graphQL = requireNonNull(graphQL, "graphQL");
        this.dataLoaderRegistry = requireNonNull(dataLoaderRegistry, "dataLoaderRegistry");
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
    }

    @Override
    protected HttpResponse executeGraphql(ServiceRequestContext ctx, GraphqlRequest req) throws Exception {
        final MediaType produceType = req.produceType();
        if (produceType == null) {
            return HttpResponse.of(HttpStatus.NOT_ACCEPTABLE, MediaType.PLAIN_TEXT,
                                   "Only application/graphql+json and application/json compatible " +
                                   "media types are acceptable");
        }

        final ExecutionInput.Builder builder = ExecutionInput.newExecutionInput(req.query());
        final Map<String, Object> variables = req.variables();
        if (!variables.isEmpty()) {
            builder.variables(variables);
        }

        final Map<String, Object> extensions = req.extensions();
        if (!extensions.isEmpty()) {
            builder.extensions(extensions);
        }

        final String operationName = req.operationName();
        if (operationName != null) {
            builder.operationName(operationName);
        }

        final ExecutionInput executionInput = builder.context(ctx)
                                                     .dataLoaderRegistry(dataLoaderRegistry)
                                                     .build();
        return execute(ctx, executionInput, produceType);
    }

    private HttpResponse execute(ServiceRequestContext ctx, ExecutionInput input, MediaType produceType) {
        final CompletableFuture<ExecutionResult> future;
        if (useBlockingTaskExecutor) {
            future = CompletableFuture.supplyAsync(() -> graphQL.execute(input), ctx.blockingTaskExecutor());
        } else {
            future = graphQL.executeAsync(input);
        }
        return HttpResponse.from(future.handle((executionResult, cause) -> {
            if (cause != null) {
                // graphQL.executeAsync() returns an error in the executionResult with getErrors().
                // Use 500 Internal Server Error because this cause might be unexpected.
                final ExecutionResult error = newExecutionResult(cause);
                return HttpResponse.ofJson(HttpStatus.INTERNAL_SERVER_ERROR, produceType,
                                           error.toSpecification());
            }
            final List<GraphQLError> errors = executionResult.getErrors();
            if (!errors.isEmpty() && errors.stream().anyMatch(ValidationError.class::isInstance)) {
                // The server SHOULD deny execution with a status code of 400 Bad Request for
                // invalidate documentation.
                return HttpResponse.ofJson(HttpStatus.BAD_REQUEST, produceType,
                                           executionResult.toSpecification());
            }
            return toHttpResponse(executionResult, produceType);
        }));
    }

    private static HttpResponse toHttpResponse(ExecutionResult executionResult, MediaType produceType) {
        // TODO: When WebSocket is implemented, it should be removed.
        if (executionResult.getData() instanceof Publisher) {
            logger.warn("executionResult.getData() returns a {} that is not supported yet.",
                        executionResult.getData().toString());
            final ExecutionResult error =
                    newExecutionResult(new UnsupportedOperationException("WebSocket is not implemented"));
            return HttpResponse.ofJson(HttpStatus.NOT_IMPLEMENTED, produceType, error.toSpecification());
        }
        return HttpResponse.ofJson(produceType, executionResult.toSpecification());
    }

    private static ExecutionResult newExecutionResult(Throwable cause) {
        return new ExecutionResultImpl(GraphqlErrorException.newErrorException()
                                                            .message(cause.getMessage())
                                                            .cause(cause)
                                                            .build());
    }
}
