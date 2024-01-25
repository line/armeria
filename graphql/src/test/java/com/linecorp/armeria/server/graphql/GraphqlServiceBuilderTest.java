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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import graphql.GraphQL;
import graphql.GraphQL.Builder;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeVisitor;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

class GraphqlServiceBuilderTest {

    @Test
    void notFoundDefaultSchemaFile() {
        assertThatThrownBy(() -> {
            new GraphqlServiceBuilder().build();
        }).isInstanceOf(IllegalStateException.class).hasMessage("Not found schema file(s)");
    }

    @Test
    void specifySchemaFile() throws Exception {
        final File graphqlSchemaFile = new File(
                getClass().getResource("/testing/graphql/test.graphqls").toURI());
        final GraphqlService service = new GraphqlServiceBuilder().schemaFile(graphqlSchemaFile).build();
        assertThat(service).isNotNull();
    }

    @Test
    void specifySchemaUrl() throws Exception {
        final GraphqlService service =
                new GraphqlServiceBuilder().schemaUrls("classpath:testing/graphql/test.graphqls")
                                           .build();
        assertThat(service).isNotNull();
    }

    @Test
    void notFoundSpecifySchemaUrl() throws Exception {
        assertThatThrownBy(() -> {
            new GraphqlServiceBuilder().schemaUrls("testing/graphql/test.graphqls")
                                       .build();
        }).isInstanceOf(UncheckedIOException.class).hasMessageContaining("java.io.FileNotFoundException");
    }

    @Test
    void specifySchema() throws Exception {
        final GraphQLSchema schema = makeGraphQLSchema();
        final GraphqlService service = new GraphqlServiceBuilder().schema(schema).build();
        assertThat(service).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("provideSpecifySchemaArguments")
    void specifySchema(List<String> urls, List<RuntimeWiringConfigurator> runtimeWiringConfigurators,
                       List<GraphQLTypeVisitor> typeVisitors) throws Exception {
        final GraphQLSchema schema = makeGraphQLSchema();
        final GraphqlServiceBuilder builder = new GraphqlServiceBuilder();
        builder.schema(schema);

        urls.forEach(builder::schemaUrls);
        runtimeWiringConfigurators.forEach(builder::runtimeWiring);
        typeVisitors.forEach(builder::typeVisitors);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot add schemaUrl(or File), runtimeWiringConfigurator and " +
                            "typeVisitor when GraphqlSchema is specified.");
    }

    private static Stream<Arguments> provideSpecifySchemaArguments() throws URISyntaxException {
        return Stream.of(
                Arguments.of(ImmutableList.of("classpath:testing/graphql/test.graphqls"), ImmutableList.of(),
                             ImmutableList.of()),
                Arguments.of(ImmutableList.of(),
                             ImmutableList.of((RuntimeWiringConfigurator) builder -> {
                                 // noop
                             }),
                             ImmutableList.of()),
                Arguments.of(ImmutableList.of(), ImmutableList.of(),
                             ImmutableList.of(new GraphQLTypeVisitorStub()))
        );
    }

    private GraphQLSchema makeGraphQLSchema() throws URISyntaxException {
        final File graphqlSchemaFile =
                new File(getClass().getResource("/testing/graphql/test.graphqls").toURI());
        final TypeDefinitionRegistry typeDefinitionRegistry = new TypeDefinitionRegistry();
        final SchemaParser parser = new SchemaParser();
        typeDefinitionRegistry.merge(parser.parse(graphqlSchemaFile));

        return new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry,
                                                          RuntimeWiring.newRuntimeWiring().build());
    }

    @Test
    void successful() throws Exception {
        final File graphqlSchemaFile =
                new File(getClass().getResource("/testing/graphql/test.graphqls").toURI());
        final GraphqlServiceBuilder builder = new GraphqlServiceBuilder();
        final GraphqlService service = builder.schemaFile(graphqlSchemaFile)
                                              .instrumentation(SimplePerformantInstrumentation.INSTANCE)
                                              .runtimeWiring(it -> {
                                                  // noop
                                              })
                                              .typeVisitors(new GraphQLTypeVisitorStub())
                                              .configureGraphql(it -> {
                                                  // noop
                                              }).build();
        assertThat(service).isNotNull();
    }

    @Test
    void bothDataLoaderConfig() {
        final DataLoader<String, String> dataLoader =
                DataLoaderFactory.newDataLoader(keys -> CompletableFuture.supplyAsync(() -> keys));

        assertThatThrownBy(() -> {
            new GraphqlServiceBuilder()
                    .dataLoaderRegistry(ctx -> new DataLoaderRegistry())
                    .configureDataLoaderRegistry(dlr -> dlr.register("dummy1", dataLoader));
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("configureDataLoaderRegistry() and dataLoaderRegistry() are mutually exclusive.");

        assertThatThrownBy(() -> {
            new GraphqlServiceBuilder()
                    .configureDataLoaderRegistry(dlr -> dlr.register("dummy1", dataLoader))
                    .dataLoaderRegistry(ctx -> new DataLoaderRegistry());
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("configureDataLoaderRegistry() and dataLoaderRegistry() are mutually exclusive.");
    }

    @Test
    void graphqlAndSchemaCannotSetTogether() throws URISyntaxException {
        final GraphQLSchema schema = makeGraphQLSchema();
        final GraphQL graphQL = new Builder(schema).build();
        assertThatThrownBy(() -> GraphqlService.builder().graphql(graphQL).schema(schema))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("are mutually exclusive.");
    }
}
