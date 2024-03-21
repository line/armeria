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

package com.linecorp.armeria.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;
import com.netflix.graphql.dgs.client.CustomGraphQLClient;
import com.netflix.graphql.dgs.client.GraphQLClient;
import com.netflix.graphql.dgs.client.GraphQLResponse;
import com.netflix.graphql.dgs.client.HttpResponse;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.graphql.GraphqlService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.schema.DataFetcher;
import graphql.schema.StaticDataFetcher;

class GraphQLTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile = new File(
                    ClassLoader.getSystemResource("testing/dgs/test.graphqls").toURI());
            final GraphqlService service =
                    GraphqlService.builder()
                                  .schemaFile(graphqlSchemaFile)
                                  .runtimeWiring(c -> {
                                      final StaticDataFetcher bar = new StaticDataFetcher("bar");
                                      c.type("Query",
                                             typeWiring -> typeWiring.dataFetcher("foo", bar));
                                      final DataFetcher<String> error = errorDataFetcher();
                                      c.type("Query",
                                             typeWiring -> typeWiring.dataFetcher("error", error));
                                  })
                                  .build();
            sb.service("/graphql", service);
        }
    };

    private static DataFetcher<String> errorDataFetcher() {
        return environment -> {
            throw new NullPointerException("npe");
        };
    }

    @Test
    void testFoo() {
        final GraphQLResponse graphQLResponse = callGraphQL("{foo}");
        assertThat(graphQLResponse.hasErrors()).isFalse();
        assertThat(graphQLResponse.getData()).containsEntry("foo", "bar");
    }

    @Test
    void testError() {
        final GraphQLResponse graphQLResponse = callGraphQL("{error}");
        assertThat(graphQLResponse.hasErrors()).isTrue();
        assertThat(graphQLResponse.getErrors())
                .element(0)
                .hasFieldOrPropertyWithValue("message", "Exception while fetching data (/error) : npe");
    }

    private static GraphQLResponse callGraphQL(String query) {
        final WebClient webClient = WebClient.of(server.httpUri());
        final GraphQLClient client = new CustomGraphQLClient("/graphql", (url, headers, body) -> {
            final RequestHeadersBuilder headersBuilder =
                    RequestHeaders.builder(HttpMethod.POST, url);

            headers.forEach(headersBuilder::add);

            final HttpRequest request = HttpRequest.of(headersBuilder.build(),
                                                       HttpData.ofUtf8(body));
            final AggregatedHttpResponse response = webClient.execute(request)
                                                             .aggregate().join();
            return new HttpResponse(response.status().code(),
                                    response.contentUtf8());
        });
        return client.executeQuery(query, ImmutableMap.of());
    }
}
