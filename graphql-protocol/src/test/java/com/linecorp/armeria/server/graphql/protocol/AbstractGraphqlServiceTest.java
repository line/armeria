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

import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

class AbstractGraphqlServiceTest {

    private TestGraphqlService testGraphqlService;

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
        assertThat(testGraphqlService.graphqlRequest.produceType()).isEqualTo(MediaType.GRAPHQL_JSON);
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
            assertThat(testGraphqlService.graphqlRequest.produceType()).isNull();
        } else {
            assertThat(testGraphqlService.graphqlRequest.produceType()).isEqualTo(mediaType);
        }
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

    private static class TestGraphqlService extends AbstractGraphqlService {

        @Nullable
        private GraphqlRequest graphqlRequest;

        @Override
        protected HttpResponse executeGraphql(ServiceRequestContext ctx, GraphqlRequest req) throws Exception {
            graphqlRequest = req;
            return null;
        }
    }
}
