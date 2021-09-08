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
import java.net.URL;
import java.util.concurrent.CompletableFuture;

import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.junit.jupiter.api.Test;

import graphql.execution.instrumentation.SimpleInstrumentation;
import graphql.schema.GraphQLSchema;
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
        final File graphqlSchemaFile = new File(getClass().getResource("/test.graphqls").toURI());
        final GraphqlService service = new GraphqlServiceBuilder().schemaFile(graphqlSchemaFile).build();
        assertThat(service).isNotNull();
    }

    @Test
    void specifySchemaUrl() throws Exception {
        final URL graphqlSchemaUrl = getClass().getResource("/test.graphqls");
        final GraphqlService service = new GraphqlServiceBuilder().schemaUrl(graphqlSchemaUrl).build();
        assertThat(service).isNotNull();
    }

    @Test
    void specifySchema() throws Exception {
        final File graphqlSchemaFile =
                new File(getClass().getResource("/test.graphqls").toURI());
        final TypeDefinitionRegistry typeDefinitionRegistry = new TypeDefinitionRegistry();
        final SchemaParser parser = new SchemaParser();
        typeDefinitionRegistry.merge(parser.parse(graphqlSchemaFile));

        final GraphQLSchema schema =
                new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry,
                                                           RuntimeWiring.newRuntimeWiring().build());
        final GraphqlService service = new GraphqlServiceBuilder().schema(schema).build();
        assertThat(service).isNotNull();
    }

    @Test
    void successful() throws Exception {
        final File graphqlSchemaFile =
                new File(getClass().getResource("/test.graphqls").toURI());
        final GraphqlServiceBuilder builder = new GraphqlServiceBuilder();
        final DataLoader<String, String> dataLoader =
                DataLoaderFactory.newDataLoader(keys -> CompletableFuture.supplyAsync(() -> keys));
        final GraphqlService service = builder.schemaFile(graphqlSchemaFile)
                                              .instrumentation(SimpleInstrumentation.INSTANCE)
                                              .configureDataLoaderRegistry(dlr -> {
                                                  dlr.register("dummy1", dataLoader);
                                              })
                                              .runtimeWiring(it -> {
                                                  // noop
                                              })
                                              .typeVisitors(new GraphQLTypeVisitorStub())
                                              .configureGraphql(it -> {
                                                  // noop
                                              }).build();
        assertThat(service).isNotNull();
    }
}
