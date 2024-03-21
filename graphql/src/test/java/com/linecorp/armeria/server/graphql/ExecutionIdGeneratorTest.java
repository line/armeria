/*
 * Copyright 2023 LINE Corporation
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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.ExecutionResult;
import graphql.GraphQLContext;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionId;
import graphql.execution.ExecutionStrategyParameters;
import graphql.schema.DataFetcher;

class ExecutionIdGeneratorTest {

    static CaptureIdStrategy idStrategy = new CaptureIdStrategy();

    @Nullable
    static volatile GraphQLContext capturedGraphQLContext;

    static class CaptureIdStrategy extends AsyncExecutionStrategy {
        @Nullable
        volatile ExecutionId executionId;

        @Override
        public CompletableFuture<ExecutionResult> execute(ExecutionContext executionContext,
                                                          ExecutionStrategyParameters parameters) {
            executionId = executionContext.getExecutionId();
            return super.execute(executionContext, parameters);
        }
    }

    static class ConcatExecutionIdGenerator implements ExecutionIdGenerator {
        @Override
        public ExecutionId generate(ServiceRequestContext requestContext, String query, String operationName,
                                    GraphQLContext graphqlContext) {
            return ExecutionId.from(requestContext + query + operationName +
                                    graphqlContext.get(GraphqlServiceContexts.GRAPHQL_CONTEXT_KEY));
        }
    }

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile =
                    new File(getClass().getResource("/testing/graphql/test.graphqls").toURI());
            final GraphqlService service =
                    GraphqlService.builder()
                                  .configureGraphql(builder -> builder.queryExecutionStrategy(idStrategy))
                                  .schemaFile(graphqlSchemaFile)
                                  .runtimeWiring(c -> {
                                      final DataFetcher<String> bar = dataFetcher("bar");
                                      c.type("Query",
                                             typeWiring -> typeWiring.dataFetcher("foo", bar));
                                  })
                                  .build();
            final GraphqlService customizedExecutionIdService =
                    GraphqlService.builder()
                                  .executionIdGenerator(new ConcatExecutionIdGenerator())
                                  .configureGraphql(builder -> builder.queryExecutionStrategy(idStrategy))
                                  .schemaFile(graphqlSchemaFile)
                                  .runtimeWiring(c -> {
                                      final DataFetcher<String> bar = dataFetcher("bar");
                                      c.type("Query",
                                             typeWiring -> typeWiring.dataFetcher("foo", bar));
                                  })
                                  .build();
            sb.service("/graphql", service)
              .service("/graphql-customized-execution-id", customizedExecutionIdService);
        }
    };

    static DataFetcher<String> dataFetcher(String value) {
        return environment -> {
            capturedGraphQLContext = environment.getGraphQlContext();
            final ServiceRequestContext ctx = GraphqlServiceContexts.get(environment);
            assertThat(ctx.eventLoop().inEventLoop()).isTrue();
            // Make sure that a ServiceRequestContext is available
            assertThat(ServiceRequestContext.current()).isSameAs(ctx);
            return value;
        };
    }

    @BeforeEach
    void beforeEach() {
        idStrategy.executionId = null;
        capturedGraphQLContext = null;
    }

    @Test
    void defaultExecutionIdGenerator() throws InterruptedException {
        final AggregatedHttpResponse response = server.blockingWebClient().get("/graphql?query={foo}");
        final ServiceRequestContext ctx = server.requestContextCaptor().take();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8()).node("data.foo").isEqualTo("bar");
        assertThat(idStrategy.executionId).isEqualTo(ExecutionId.from(ctx.id().text()));
    }

    @Test
    void concatenateExecutionIdGenerator() throws InterruptedException {
        final String operationName = "Foo";
        final String query = "query " + operationName + " {foo}";
        final String content = "{\n" +
                               "  \"operationName\": \"" + operationName + "\",\n" +
                               "  \"query\": \"" + query + "\"\n" +
                               "}\n";
        final HttpRequest request = HttpRequest.builder().post("/graphql-customized-execution-id")
                                               .content(MediaType.JSON, content)
                                               .build();
        final AggregatedHttpResponse response = server.blockingWebClient().execute(request);
        final ServiceRequestContext ctx = server.requestContextCaptor().take();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8()).node("data.foo").isEqualTo("bar");
        assertThat(idStrategy.executionId).isEqualTo(
                ExecutionId.from(ctx + query + operationName +
                                 capturedGraphQLContext.get(GraphqlServiceContexts.GRAPHQL_CONTEXT_KEY)));
    }
}
