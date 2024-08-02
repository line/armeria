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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.GraphqlErrorException;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

class GraphqlErrorHandlerTest {

    private static final AtomicBoolean shouldFailRequests = new AtomicBoolean();

    private static GraphQL newGraphQL() throws Exception {
        final File graphqlSchemaFile =
                new File(GraphqlErrorHandlerTest.class.getResource("/testing/graphql/test.graphqls").toURI());
        final SchemaParser schemaParser = new SchemaParser();
        final SchemaGenerator schemaGenerator = new SchemaGenerator();
        final TypeDefinitionRegistry typeRegistry = new TypeDefinitionRegistry();
        typeRegistry.merge(schemaParser.parse(graphqlSchemaFile));
        final RuntimeWiring.Builder runtimeWiringBuilder = RuntimeWiring.newRuntimeWiring();
        final DataFetcher<String> foo = dataFetcher("foo");
        runtimeWiringBuilder.type("Query",
                                  typeWiring -> typeWiring.dataFetcher("foo", foo));
        final DataFetcher<String> error = dataFetcher("error");
        runtimeWiringBuilder.type("Query",
                                  typeWiring -> typeWiring.dataFetcher("error", error));

        final GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeRegistry,
                                                                                 runtimeWiringBuilder.build());
        final Instrumentation instrumentation = new Instrumentation() {
            @Override
            public InstrumentationState createState(
                    InstrumentationCreateStateParameters parameters) {
                if (shouldFailRequests.get()) {
                    throw new AnticipatedException("external exception");
                } else {
                    return Instrumentation.super.createState(parameters);
                }
            }
        };

        return new GraphQL.Builder(graphQLSchema)
                .instrumentation(instrumentation)
                .build();
    }

    private static final GraphqlErrorHandler errorHandler
            = (ctx, input, result, cause) -> {
        if (result == null) {
            assertThat(cause).isNotNull();
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT,
                                   cause.getMessage());
        }
        final List<GraphQLError> errors = result.getErrors();
        if (errors.stream().map(GraphQLError::getMessage).anyMatch(m -> m.endsWith("foo"))) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }
        return null;
    };

    private static DataFetcher<String> dataFetcher(String value) {
        return environment -> {
            final ServiceRequestContext ctx = GraphqlServiceContexts.get(environment);
            // Make sure that a ServiceRequestContext is available
            assertThat(ServiceRequestContext.current()).isSameAs(ctx);
            throw GraphqlErrorException.newErrorException().message(value).build();
        };
    }

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            final GraphqlService service =
                    GraphqlService.builder()
                                  .graphql(newGraphQL())
                                  .errorHandler(errorHandler)
                                  .build();
            sb.service("/graphql", service);
        }
    };

    @RegisterExtension
    static ServerExtension blockingServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            final GraphqlService service =
                    GraphqlService.builder()
                                  .graphql(newGraphQL())
                                  .useBlockingTaskExecutor(true)
                                  .errorHandler(errorHandler)
                                  .build();
            sb.service("/graphql", service);
        }
    };

    @BeforeEach
    void setUp() {
        shouldFailRequests.set(false);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void handledError(boolean blocking) {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(MediaType.GRAPHQL, "{foo}")
                                               .build();
        final ServerExtension server = blocking ? blockingServer : GraphqlErrorHandlerTest.server;
        final AggregatedHttpResponse response = server.blockingWebClient().execute(request);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void unhandledGraphqlError(boolean blocking) {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(MediaType.GRAPHQL, "{error}")
                                               .build();
        final ServerExtension server = blocking ? blockingServer : GraphqlErrorHandlerTest.server;
        final AggregatedHttpResponse response = server.blockingWebClient().execute(request);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void unhandledException(boolean blocking) {
        shouldFailRequests.set(true);
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(MediaType.GRAPHQL, "{error}")
                                               .build();
        final ServerExtension server = blocking ? blockingServer : GraphqlErrorHandlerTest.server;
        final AggregatedHttpResponse response = server.blockingWebClient().execute(request);
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.contentUtf8()).isEqualTo("external exception");
    }
}
