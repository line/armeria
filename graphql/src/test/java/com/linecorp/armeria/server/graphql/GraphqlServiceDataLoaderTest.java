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

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.dataloader.DataLoaderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.schema.DataFetcher;

class GraphqlServiceDataLoaderTest {

    private static final ConcurrentMap<DataLoaderRegistry, Boolean> REGISTRIES = new ConcurrentHashMap<>();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile =
                    new File(getClass().getResource("/testing/graphql/test.graphqls").toURI());
            final GraphqlService service =
                    GraphqlService.builder()
                                  .schemaFile(graphqlSchemaFile)
                                  .dataLoaderRegistry(ctx -> new DataLoaderRegistry())
                                  .runtimeWiring(c -> {
                                      final DataFetcher<String> bar = dataFetcher("bar");
                                      c.type("Query",
                                             typeWiring -> typeWiring.dataFetcher("foo", bar));
                                  })
                                  .build();
            sb.service("/graphql", service);
        }
    };

    private static DataFetcher<String> dataFetcher(String value) {
        return environment -> {
            REGISTRIES.put(environment.getDataLoaderRegistry(), true);
            return value;
        };
    }

    @BeforeEach
    void beforeEach() {
        REGISTRIES.clear();
    }

    @Test
    void shouldCreatePerRequest() {
        BlockingWebClient.of(server.httpUri()).get("/graphql?query={foo}");
        BlockingWebClient.of(server.httpUri()).get("/graphql?query={foo}");

        assertThat(REGISTRIES).hasSize(2);
    }
}
