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

import org.junit.jupiter.api.Test;

import graphql.execution.instrumentation.SimpleInstrumentation;
import graphql.schema.GraphQLTypeVisitorStub;

class GraphQLServiceBuilderTest {

    @Test
    void notFoundDefaultSchemaFile() {
        assertThatThrownBy(() -> {
            new GraphQLServiceBuilder().build();
        }).isInstanceOf(IllegalStateException.class).hasMessage("Not found schema file(s)");
    }

    @Test
    void specifySchemaFile() throws Exception {
        final File graphqlSchemaFile = new File(ClassLoader.getSystemResource("test.graphqls").toURI());
        final GraphQLService service = new GraphQLServiceBuilder().schemaFile(graphqlSchemaFile).build();
        assertThat(service).isNotNull();
    }

    @Test
    void successful() throws Exception {
        final File graphqlSchemaFile = new File(ClassLoader.getSystemResource("test.graphqls").toURI());
        final GraphQLServiceBuilder builder = new GraphQLServiceBuilder();
        final GraphQLService service = builder.schemaFile(graphqlSchemaFile)
                                              .instrumentation(SimpleInstrumentation.INSTANCE)
                                              .runtimeWiring(it -> {
                                                  // noop
                                              }).typeVisitors(new GraphQLTypeVisitorStub())
                                              .configure(it -> {
                                                  // noop
                                              }).build();
        assertThat(service).isNotNull();
    }
}
