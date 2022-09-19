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
import java.util.concurrent.CompletableFuture;

import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

final class DefaultGraphqlService extends AbstractGraphqlService implements GraphqlService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultGraphqlService.class);

    private final GraphQL graphQL;

    private final DataLoaderRegistry dataLoaderRegistry;

    private final boolean useBlockingTaskExecutor;

    private final GraphqlErrorsHandler errorsHandler;

    DefaultGraphqlService(GraphQL graphQL, DataLoaderRegistry dataLoaderRegistry,
                          boolean useBlockingTaskExecutor, GraphqlErrorsHandler errorsHandler) {
        this.graphQL = requireNonNull(graphQL, "graphQL");
        this.dataLoaderRegistry = requireNonNull(dataLoaderRegistry, "dataLoaderRegistry");
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        this.errorsHandler = errorsHandler;
    }

    @Override
    protected HttpResponse executeGraphql(ServiceRequestContext ctx, GraphqlRequest req) throws Exception {
        final MediaType produceType = GraphqlUtil.produceType(ctx.request().headers());
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

        final ExecutionInput executionInput =
                builder.context(ctx)
                       .graphQLContext(GraphqlServiceContexts.graphqlContext(ctx))
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

        return HttpResponse.from(
                future.handle((executionResult, cause) ->
                                      errorsHandler.handle(ctx, input, produceType, executionResult, cause)));
    }
}
