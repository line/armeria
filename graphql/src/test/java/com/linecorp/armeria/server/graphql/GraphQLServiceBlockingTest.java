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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.schema.DataFetcher;

class GraphQLServiceBlockingTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile =
                    new File(getClass().getResource("/test.graphqls").toURI());
            final GraphQLService graphQLService =
                    GraphQLService.builder()
                                  .schemaFile(graphqlSchemaFile)
                                  .useBlockingTaskExecutor(true)
                                  .runtimeWiring(c -> {
                                      final DataFetcher<String> bar = dataFetcher("bar");
                                      c.type("Query",
                                             typeWiring -> typeWiring.dataFetcher("foo", bar));
                                  })
                                  .build();
            sb.service("/graphql", graphQLService);
        }
    };

    private static DataFetcher<String> dataFetcher(String value) {
        return environment -> {
            final ServiceRequestContext ctx = environment.getContext();
            assertThat(ctx.eventLoop().inEventLoop()).isFalse();
            return value;
        };
    }

    @Test
    void testBlocking() {
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .get("/graphql?query={foo}")
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8()).node("data.foo").isEqualTo("bar");
    }
}
