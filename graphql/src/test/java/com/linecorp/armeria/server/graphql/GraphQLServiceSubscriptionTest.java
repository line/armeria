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
import java.util.List;
import java.util.Map;

import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Publisher;

import com.google.common.base.Charsets;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.schema.DataFetcher;
import graphql.schema.StaticDataFetcher;

class GraphQLServiceSubscriptionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile =
                    new File(getClass().getResource("/subscription.graphqls").toURI());
            final GraphQLService graphQLService =
                    GraphQLService.builder()
                                  .schemaFile(graphqlSchemaFile)
                                  .runtimeWiring(c -> {
                                      final StaticDataFetcher bar = new StaticDataFetcher("bar");
                                      c.type("Query",
                                             typeWiring -> typeWiring.dataFetcher("foo", bar));
                                      c.type("Subscription",
                                             typeWiring -> typeWiring.dataFetcher("hello", dataFetcher()));
                                  })
                                  .build();
            sb.service("/graphql", graphQLService);
        }
    };

    private static DataFetcher<Publisher<String>> dataFetcher() {
        return environment -> StreamMessage.of("Armeria");
    }

    @Test
    void testSubscription() throws Exception {
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .path("/graphql")
                                                     .method(HttpMethod.POST)
                                                     .contentType(MediaType.GRAPHQL)
                                                     .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(headers,
                                                                  "subscription {hello}",
                                                                  Charsets.UTF_8)
                                                         .aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .withMatcher("errors",
                             new CustomTypeSafeMatcher<List<Map<String, String>>>("errors") {
                                 @Override
                                 protected boolean matchesSafely(List<Map<String, String>> item) {
                                     final Map<String, String> error = item.get(0);
                                     final String message = "WebSocket is not implemented";
                                     return message.equals(error.get("message"));
                                 }
                             });
    }
}
