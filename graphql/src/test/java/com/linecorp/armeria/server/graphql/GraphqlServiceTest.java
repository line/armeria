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
import java.util.stream.Stream;

import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.schema.DataFetcher;
import graphql.schema.StaticDataFetcher;

class GraphqlServiceTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile =
                    new File(getClass().getResource("/test.graphqls").toURI());
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
            final ServiceRequestContext ctx = environment.getContext();
            assertThat(ctx.eventLoop().inEventLoop()).isTrue();
            throw new NullPointerException("npe");
        };
    }

    @Test
    void shouldGet() {
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .get("/graphql?query={foo}")
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8()).node("data.foo").isEqualTo("bar");
    }

    @Test
    void shouldGetWithoutQuery() {
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .get("/graphql")
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).isEqualTo("Missing query");
    }

    @Test
    void shouldPostWhenMediaTypeIsGraphql() {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(MediaType.GRAPHQL, "{foo}")
                                               .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(request)
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8()).node("data.foo").isEqualTo("bar");
    }

    @Test
    void shouldPostWhenMediaTypeIsGraphqlPlusJson() {
        final MediaType graphqlPlusJson = MediaType.create("application", "graphql+json");
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(graphqlPlusJson, "{\"query\": \"{foo}\"}")
                                               .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(request)
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8()).node("data.foo").isEqualTo("bar");
    }

    @Test
    void shouldPostWhenMediaTypeIsJson() {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(MediaType.JSON, "{\"query\": \"{foo}\"}")
                                               .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(request)
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8()).node("data.foo").isEqualTo("bar");
    }

    @Test
    void shouldPostWhenBodyIsEmpty() {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(MediaType.JSON, "")
                                               .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(request)
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).isEqualTo("Missing request body");
    }

    @Test
    void shouldPostWhenMediaTypeIsEmpty() {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(request)
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.contentUtf8()).isEqualTo("Unsupported media type. Only JSON compatible types and " +
                                                     "application/graphql are supported.");
    }

    @ParameterizedTest
    @MethodSource("provideMediaTypeArguments")
    void shouldPostWhenMediaTypeIsNotSupported(MediaType mediaType) {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(mediaType, "{\"query\": \"{foo}\"}")
                                               .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(request)
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.contentUtf8()).isEqualTo("Unsupported media type. Only JSON compatible types and " +
                                                     "application/graphql are supported.");
    }

    private static Stream<Arguments> provideMediaTypeArguments() {
        return Stream.of(
                Arguments.of(MediaType.ANY_APPLICATION_TYPE),
                Arguments.of(MediaType.ANY_TYPE),
                Arguments.of(MediaType.AAC_AUDIO),
                Arguments.of(MediaType.ANY_TEXT_TYPE),
                Arguments.of(MediaType.ANY_IMAGE_TYPE),
                Arguments.of(MediaType.ATOM_UTF_8),
                Arguments.of(MediaType.ANY_APPLICATION_TYPE),
                Arguments.of(MediaType.FORM_DATA)
        );
    }

    @Test
    void shouldPostWhenError() {
        final HttpRequest request = HttpRequest.builder().post("/graphql")
                                               .content(MediaType.GRAPHQL, "{error}")
                                               .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(request)
                                                         .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .withMatcher("errors",
                             new CustomTypeSafeMatcher<List<Map<String, String>>>("errors") {
                                 @Override
                                 protected boolean matchesSafely(List<Map<String, String>> item) {
                                     final Map<String, String> error = item.get(0);
                                     final String message = "Exception while fetching data (/error) : npe";
                                     return message.equals(error.get("message"));
                                 }
                             })
                .withMatcher("data",
                             new CustomTypeSafeMatcher<Map<String, String>>("data") {
                                 @Override
                                 protected boolean matchesSafely(Map<String, String> item) {
                                     return item.get("error") == null;
                                 }
                             });
    }
}
