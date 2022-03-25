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

package com.linecorp.armeria.server.graphql.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.graphql.protocol.GraphqlRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.server.graphql.protocol.GraphqlUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AbstractGraphqlServiceTest {

    private TestGraphqlService testGraphqlService;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/graphql", new TestGraphqlService());
        }
    };

    @BeforeEach
    void setUp() {
        testGraphqlService = new TestGraphqlService();
    }

    @Test
    void shouldNotAllowMutationForGetRequests() throws Exception {
        final QueryParams query = QueryParams.of("query", "mutation={addPet(id: \"1\") {name}}");
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/graphql?" + query.toQueryString());
        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        final AggregatedHttpResponse response = testGraphqlService.serve(ctx, request).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void defaultContentType() throws Exception {
        final QueryParams query = QueryParams.of("query", "query={users(id: \"1\") {name}}");
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/graphql?" + query.toQueryString());
        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        testGraphqlService.serve(ctx, request);
        assertThat(testGraphqlService.produceType).isEqualTo(MediaType.GRAPHQL_JSON);
    }

    @ArgumentsSource(MediaTypeProvider.class)
    @ParameterizedTest
    void respectAcceptEncoding(MediaType mediaType) throws Exception {
        final QueryParams query = QueryParams.of("query", "query={users(id: \"1\") {name}}");
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.GET, "/graphql?" + query.toQueryString())
                              .addObject(HttpHeaderNames.ACCEPT, mediaType)
                              .build();

        final HttpRequest request = HttpRequest.of(headers);
        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        testGraphqlService.serve(ctx, request);
        if (mediaType == MediaType.PLAIN_TEXT) {
            // May be rejected by implementation
            assertThat(testGraphqlService.produceType).isNull();
        } else {
            assertThat(testGraphqlService.produceType).isEqualTo(mediaType);
        }
    }

    @MethodSource("providePostMethodArguments")
    @ParameterizedTest
    void post(Map<String, Object> content, HttpStatus status) throws Exception {
        final HttpRequest request = HttpRequest.builder()
                .post("/graphql")
                .contentJson(content)
                .build();

        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        final AggregatedHttpResponse response = testGraphqlService.serve(ctx, request).aggregate().join();
        assertThat(response.status()).isEqualTo(status);
    }

    @MethodSource("provideThrowsExceptionPostMethodArguments")
    @ParameterizedTest
    void throwsExceptionPost(Map<Object, Object> content) throws Exception {
        final HttpRequest request = HttpRequest.builder()
                                               .post("/graphql")
                                               .contentJson(content)
                                               .build();

        final BlockingWebClient client = server.webClient().blocking();
        final AggregatedHttpResponse response = client.execute(request);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        final RequestLog log = server.requestContextCaptor().take().log().whenComplete().join();
        assertThat(log.responseCause()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeVariablesAndExtensionsAsMap() throws Exception {
        final QueryParams query = QueryParams.of("query", "query={users(id: \"1\") {name}}",
                                                 "variables", "{\"foo\": 1, \"bar\": \"A\"}",
                                                 "extensions", "{\"abc\": 2, \"def\": \"B\"}");
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.GET, "/graphql?" + query.toQueryString())
                              .build();

        final HttpRequest request = HttpRequest.of(headers);
        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        testGraphqlService.serve(ctx, request);
        assertThat(testGraphqlService.graphqlRequest.variables())
                .isEqualTo(ImmutableMap.of("foo", 1, "bar", "A"));
        assertThat(testGraphqlService.graphqlRequest.extensions())
                .isEqualTo(ImmutableMap.of("abc", 2, "def", "B"));
    }

    private static class MediaTypeProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(MediaType.GRAPHQL_JSON, MediaType.JSON, MediaType.PLAIN_TEXT)
                         .map(Arguments::of);
        }
    }

    private static Stream<Arguments> providePostMethodArguments() {
        return Stream.of(
                Arguments.of(ImmutableMap.of(), HttpStatus.BAD_REQUEST),
                Arguments.of(ImmutableMap.of("query", ""), HttpStatus.BAD_REQUEST),
                Arguments.of(ImmutableMap.of("query", "{users(id: \"1\") {name}}"), HttpStatus.OK),
                Arguments.of(ImmutableMap.of("query", "{users(id: \"1\") {name}}",
                                             "operationName", "dummy"), HttpStatus.OK),
                Arguments.of(ImmutableMap.of("query", "{users(id: \"1\") {name}}",
                                             "variables", ImmutableMap.of()), HttpStatus.OK),
                Arguments.of(ImmutableMap.of("query", "{users(id: \"1\") {name}}",
                                             "extensions", ImmutableMap.of()), HttpStatus.OK)
        );
    }

    private static Stream<Arguments> provideThrowsExceptionPostMethodArguments() {
        return Stream.of(
                Arguments.of(ImmutableMap.of("query", ImmutableMap.of())),
                Arguments.of(ImmutableMap.of("query", "{users(id: \"1\") {name}}",
                                             "operationName", ImmutableMap.of())),
                Arguments.of(ImmutableMap.of("query", "{users(id: \"1\") {name}}",
                                             "variables", "variables_string")),
                Arguments.of(ImmutableMap.of("query", "{users(id: \"1\") {name}}",
                                             "extensions", "extension_string"))
        );
    }

    private static class TestGraphqlService extends AbstractGraphqlService {

        @Nullable
        private GraphqlRequest graphqlRequest;
        @Nullable
        private MediaType produceType;

        @Override
        protected HttpResponse executeGraphql(ServiceRequestContext ctx, GraphqlRequest req) throws Exception {
            graphqlRequest = req;
            produceType = GraphqlUtil.produceType(ctx.request().headers());
            return HttpResponse.of(HttpStatus.OK);
        }
    }
}
