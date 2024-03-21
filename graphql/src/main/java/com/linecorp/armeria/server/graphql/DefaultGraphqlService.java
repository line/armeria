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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.dataloader.DataLoaderRegistry;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.graphql.protocol.GraphqlRequest;
import com.linecorp.armeria.internal.server.graphql.protocol.GraphqlUtil;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.graphql.protocol.AbstractGraphqlService;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;

final class DefaultGraphqlService extends AbstractGraphqlService implements GraphqlService, GraphqlExecutor {

    private static final Logger logger = LoggerFactory.getLogger(DefaultGraphqlService.class);

    private final GraphQL graphQL;

    private final Function<? super ServiceRequestContext,
                           ? extends DataLoaderRegistry> dataLoaderRegistryFunction;

    private final boolean useBlockingTaskExecutor;

    private final GraphqlErrorHandler errorHandler;

    DefaultGraphqlService(
            GraphQL graphQL,
            Function<? super ServiceRequestContext, ? extends DataLoaderRegistry> dataLoaderRegistryFunction,
            boolean useBlockingTaskExecutor, GraphqlErrorHandler errorHandler) {
        this.graphQL = requireNonNull(graphQL, "graphQL");
        this.dataLoaderRegistryFunction = requireNonNull(dataLoaderRegistryFunction,
                                                         "dataLoaderRegistryFunction");
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        this.errorHandler = errorHandler;
    }

    @Override
    protected HttpResponse executeGraphql(ServiceRequestContext ctx, GraphqlRequest req) throws Exception {
        final MediaType produceType = GraphqlUtil.produceType(ctx.request().headers());
        if (produceType == null) {
            return HttpResponse.of(HttpStatus.NOT_ACCEPTABLE, MediaType.PLAIN_TEXT,
                                   "Only %s and %s compatible media types are acceptable",
                                   MediaType.GRAPHQL_RESPONSE_JSON, MediaType.JSON);
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

        final ExecutionInput executionInput =
                builder.context(ctx)
                       .graphQLContext(GraphqlServiceContexts.graphqlContext(ctx))
                       .dataLoaderRegistry(dataLoaderRegistryFunction.apply(ctx))
                       .build();
        return execute(ctx, executionInput, produceType);
    }

    @Override
    public CompletableFuture<ExecutionResult> executeGraphql(ServiceRequestContext ctx, ExecutionInput input) {
        if (useBlockingTaskExecutor) {
            return CompletableFuture.supplyAsync(() -> graphQL.execute(input),
                                                 ctx.blockingTaskExecutor());
        } else {
            return graphQL.executeAsync(input);
        }
    }

    private HttpResponse execute(
            ServiceRequestContext ctx, ExecutionInput input, MediaType produceType) {
        final CompletableFuture<ExecutionResult> future = executeGraphql(ctx, input);
        return HttpResponse.of(
                future.handle((executionResult, cause) -> {
                    if (executionResult.getData() instanceof Publisher) {
                        logger.warn("executionResult.getData() returns a {} that is not supported yet.",
                                    executionResult.getData().toString());

                        return HttpResponse.ofJson(HttpStatus.NOT_IMPLEMENTED,
                                                   produceType,
                                                   toSpecification(
                                                           "Use GraphQL over WebSocket for subscription"));
                    }

                    if (executionResult.getErrors().isEmpty() && cause == null) {
                        return HttpResponse.ofJson(produceType, executionResult.toSpecification());
                    }

                    return errorHandler.handle(ctx, input, executionResult, cause);
                }));
    }

    static Map<String, Object> toSpecification(String message) {
        requireNonNull(message, "message");
        final Map<String, Object> error = ImmutableMap.of("message", message);
        return ImmutableMap.of("errors", ImmutableList.of(error));
    }
}
