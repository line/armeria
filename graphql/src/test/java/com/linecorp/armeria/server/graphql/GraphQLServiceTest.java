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

import com.google.common.base.Charsets;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.StaticDataFetcher;

class GraphQLServiceTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile = new File(ClassLoader.getSystemResource("test.graphqls").toURI());
            sb.service("/graphql", GraphQLService.builder()
                                                 .schemaFile(graphqlSchemaFile)
                                                 .runtimeWiring(c -> {
                                                     final StaticDataFetcher bar = new StaticDataFetcher("bar");
                                                     c.type("Query",
                                                            typeWiring -> typeWiring.dataFetcher("foo", bar));
                                                     final ErrorDataFetcher error = new ErrorDataFetcher();
                                                     c.type("Query",
                                                            typeWiring -> typeWiring
                                                                    .dataFetcher("error", error));
                                                 })
                                                 .build());
        }
    };

    private static class ErrorDataFetcher implements DataFetcher<String> {

        @Override
        public String get(DataFetchingEnvironment environment) throws Exception {
            throw new NullPointerException("npe");
        }
    }

    @Test
    void shouldGet() throws Exception {
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .get("/graphql?query={foo}")
                                                         .aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("{\"data\":{\"foo\":\"bar\"}}");
    }

    @Test
    void shouldGetWithoutQuery() throws Exception {
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .get("/graphql")
                                                         .aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).isEqualTo("Query is required");
    }

    @Test
    void shouldPostWhenMediaTypeIsGraphql() throws Exception {
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .path("/graphql")
                                                     .method(HttpMethod.POST)
                                                     .contentType(MediaType.GRAPHQL)
                                                     .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(headers, "{foo}", Charsets.UTF_8)
                                                         .aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("{\"data\":{\"foo\":\"bar\"}}");
    }

    @Test
    void shouldPostWhenMediaTypeIsGraphqlPlusJson() throws Exception {
        final MediaType graphqlPlusJson = MediaType.create("application", "graphql+json");
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .path("/graphql")
                                                     .method(HttpMethod.POST)
                                                     .contentType(graphqlPlusJson)
                                                     .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(headers,
                                                                  "{\"query\": \"{foo}\"}",
                                                                  Charsets.UTF_8)
                                                         .aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("{\"data\":{\"foo\":\"bar\"}}");
    }

    @Test
    void shouldPostWhenMediaTypeIsJson() throws Exception {
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .path("/graphql")
                                                     .method(HttpMethod.POST)
                                                     .contentType(MediaType.JSON)
                                                     .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(headers,
                                                                  "{\"query\": \"{foo}\"}",
                                                                  Charsets.UTF_8)
                                                         .aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("{\"data\":{\"foo\":\"bar\"}}");
    }

    @Test
    void shouldPostWhenBodyIsEmpty() throws Exception {
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .path("/graphql")
                                                     .method(HttpMethod.POST)
                                                     .contentType(MediaType.JSON)
                                                     .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(headers,
                                                                  "",
                                                                  Charsets.UTF_8)
                                                         .aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).isEqualTo("Body is required");
    }

    @Test
    void shouldPostWhenMediaTypeIsEmpty() throws Exception {
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .path("/graphql")
                                                     .method(HttpMethod.POST)
                                                     .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(headers,
                                                                  "{\"query\": \"{foo}\"}",
                                                                  Charsets.UTF_8)
                                                         .aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.contentUtf8()).isEqualTo("Could not process GraphQL request");
    }

    @ParameterizedTest
    @MethodSource("provideMediaTypeArguments")
    void shouldPostWhenMediaTypeIsNotSupported(MediaType mediaType) throws Exception {
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .path("/graphql")
                                                     .method(HttpMethod.POST)
                                                     .contentType(mediaType)
                                                     .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(headers,
                                                                  "{\"query\": \"{foo}\"}",
                                                                  Charsets.UTF_8)
                                                         .aggregate().get();

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.contentUtf8()).isEqualTo("Could not process GraphQL request");
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
    void shouldPostWhenError() throws Exception {
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .path("/graphql")
                                                     .method(HttpMethod.POST)
                                                     .contentType(MediaType.GRAPHQL)
                                                     .build();
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .execute(headers, "{error}", Charsets.UTF_8)
                                                         .aggregate().get();

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
