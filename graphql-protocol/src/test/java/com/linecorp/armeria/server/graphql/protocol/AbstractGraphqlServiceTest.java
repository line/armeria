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

import java.util.List;
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
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
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.multipart.MultipartFile;
import com.linecorp.armeria.internal.server.graphql.protocol.GraphqlUtil;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@GenerateNativeImageTrace
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
        assertThat(testGraphqlService.produceType).isEqualTo(MediaType.JSON);
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
    void throwsExceptionPost(Map<Object, Object> content, String message) {
        final HttpRequest request = HttpRequest.builder()
                                               .post("/graphql")
                                               .contentJson(content)
                                               .build();

        final BlockingWebClient client = server.webClient().blocking();
        final AggregatedHttpResponse response = client.execute(request);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains(message);
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

    @MethodSource("provideMultipartPostMethodArguments")
    @ParameterizedTest
    void multipartPost(List<BodyPart> bodyParts, HttpStatus status) throws Exception {
        final HttpRequest request = Multipart.of(bodyParts).toHttpRequest("/graphql");

        final BlockingWebClient client = server.webClient().blocking();
        final AggregatedHttpResponse response = client.execute(request);
        assertThat(response.status()).isEqualTo(status);
    }

    @MethodSource("provideThrowsExceptionMultipartPostMethodArguments")
    @ParameterizedTest
    void throwsExceptionMultipartPost(List<BodyPart> bodyParts, String message) {
        final HttpRequest request = Multipart.of(bodyParts).toHttpRequest("/graphql");

        final BlockingWebClient client = server.webClient().blocking();
        final AggregatedHttpResponse response = client.execute(request);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains(message);
    }

    @Test
    void multipartSingleFile() throws Exception {
        final String query = "{users(id: \\\"1\\\") {name}}";
        final String variables = "{\"file\": null}";
        final HttpRequest request = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "operations"),
                            String.format("{ \"query\": \"%s\", \"variables\": %s}", query, variables)),
                BodyPart.of(ContentDisposition.of("form-data", "map"),
                            "{\"0\": [\"variables.file\"]}"),
                BodyPart.of(ContentDisposition.of("form-data", "0", "test.txt"),
                            "Hello!")
        ).toHttpRequest("/graphql");
        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        testGraphqlService.serve(ctx, request).aggregate().join();
        final MultipartFile multipartFile =
                (MultipartFile) testGraphqlService.graphqlRequest.variables().get("file");
        assertThat(multipartFile.path()).hasContent("Hello!");
    }

    @Test
    void multipartFileList() throws Exception {
        final String query = "{users(id: \\\"1\\\") {name}}";
        final String variables = "{\"files\": [null, null]}";
        final HttpRequest request = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "operations"),
                            String.format("{ \"query\": \"%s\", \"variables\": %s}", query, variables)),
                BodyPart.of(ContentDisposition.of("form-data", "map"),
                            "{\"0\": [\"variables.files.0\"],\"1\": [\"variables.files.1\"]}"),
                BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                            "foo"),
                BodyPart.of(ContentDisposition.of("form-data", "1", "bar.txt"),
                            "bar")
        ).toHttpRequest("/graphql");
        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        testGraphqlService.serve(ctx, request).aggregate().join();
        final List<MultipartFile> multipartFiles =
                (List<MultipartFile>) testGraphqlService.graphqlRequest.variables().get("files");
        assertThat(multipartFiles.get(0).path()).hasContent("foo");
        assertThat(multipartFiles.get(1).path()).hasContent("bar");
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
                Arguments.of(ImmutableMap.of("query", ImmutableMap.of()),
                             "Invalid query"),
                Arguments.of(ImmutableMap.of("query", "{users(id: \"1\") {name}}",
                                             "operationName", ImmutableMap.of()),
                             "Invalid operationName"),
                Arguments.of(ImmutableMap.of("query", "{users(id: \"1\") {name}}",
                                             "variables", "variables_string"),
                             "Unknown parameter type variables"),
                Arguments.of(ImmutableMap.of("query", "{users(id: \"1\") {name}}",
                                             "extensions", "extension_string"),
                             "Unknown parameter type variables")
        );
    }

    private static Stream<Arguments> provideMultipartPostMethodArguments() {
        return Stream.of(
                Arguments.of(ImmutableList.of(
                        BodyPart.of(ContentDisposition.of("form-data", "0", "dummy.txt"),
                                    "dummy")
                ), HttpStatus.BAD_REQUEST)
        );
    }

    private static Stream<Arguments> provideThrowsExceptionMultipartPostMethodArguments() {
        return Stream.of(
                Arguments.of(ImmutableList.of(BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                                          "")),
                             "form field is missing"),
                Arguments.of(ImmutableList.of(BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                                          "[]")),
                             "form field is missing"),
                Arguments.of(ImmutableList.of(BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                                          "{\"query\":\"{foo}\"}")),
                             "form field is missing"),
                Arguments.of(ImmutableList.of(BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                                          "{\"query\":\"{foo}\"}"),
                                              BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                                          "{\"query\":\"{foo}\"}")),
                             "More than one 'operations' received."),
                Arguments.of(ImmutableList.of(BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                                          "{\"query\":\"{foo}\"}"),
                                              BodyPart.of(ContentDisposition.of("form-data", "map"),
                                                          "{\"0\": [ \"invalid\" ]}"),
                                              BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                                                          "foo")),
                             "Invalid object-path"),
                Arguments.of(ImmutableList.of(BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                                          "{\"query\":\"{foo}\"}"),
                                              BodyPart.of(ContentDisposition.of("form-data", "map"),
                                                          "{\"0\": [ \"invalid.file\" ]}"),
                                              BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                                                          "foo")),
                             "Can only map into variables"),
                Arguments.of(ImmutableList.of(
                        BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                    "{\"query\":\"{foo}\",\"variables\":{\"file\":{}}}"),
                        BodyPart.of(ContentDisposition.of("form-data", "map"),
                                    "{\"0\": [ \"variables.file\" ]}"),
                        BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                                    "foo")),
                             "Expected null value when mapping"),
                Arguments.of(ImmutableList.of(
                        BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                    "{\"query\":\"{foo}\",\"variables\":{\"files\":[null]}}"),
                        BodyPart.of(ContentDisposition.of("form-data", "map"),
                                    "{\"0\": [ \"variables.files.1\" ]}"),
                        BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                                    "foo")),
                             "Expected null value when mapping"),
                Arguments.of(ImmutableList.of(
                        BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                    "{\"query\":\"{foo}\",\"variables\":{\"files\":[[null]]}}"),
                        BodyPart.of(ContentDisposition.of("form-data", "map"),
                                    "{\"0\": [ \"variables.files.0.a\" ]}"),
                        BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                                    "foo")),
                             "Expected null value when mapping"),
                Arguments.of(ImmutableList.of(
                        BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                    "{\"query\":\"{foo}\",\"variables\":{\"files\":[null]}}"),
                        BodyPart.of(ContentDisposition.of("form-data", "map"),
                                    "{\"0\": [ \"variables.files.1\" ]}"),
                        BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                                    "foo")),
                             "Expected null value when mapping"),
                Arguments.of(ImmutableList.of(
                        BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                    "{\"query\":\"{foo}\",\"variables\":{}}"),
                        BodyPart.of(ContentDisposition.of("form-data", "map"),
                                    "{\"0\": [ \"variables.file.0\" ]}"),
                        BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                                    "foo")),
                             "Found null intermediate value when trying to map"),
                Arguments.of(ImmutableList.of(
                        BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                    "{\"query\":\"{foo}\",\"variables\":{\"files\":[[null]]}}"),
                        BodyPart.of(ContentDisposition.of("form-data", "map"),
                                    "{\"0\": [ \"variables.files.a.0\" ]}"),
                        BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                                    "foo")),
                             "Found null intermediate value when trying to map"),
                Arguments.of(ImmutableList.of(
                        BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                    "{\"query\":\"{foo}\",\"variables\":{\"files\":[[null]]}}"),
                        BodyPart.of(ContentDisposition.of("form-data", "map"),
                                    "{\"0\": [ \"variables.files.1.0\" ]}"),
                        BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                                    "foo")),
                             "Found null intermediate value when trying to map"),
                Arguments.of(ImmutableList.of(
                        BodyPart.of(ContentDisposition.of("form-data", "operations"),
                                    "{\"query\":\"{foo}\",\"variables\":{\"files\":[[null]]}}"),
                        BodyPart.of(ContentDisposition.of("form-data", "map"),
                                    "{\"0\": [ \"variables.files.-1.0\" ]}"),
                        BodyPart.of(ContentDisposition.of("form-data", "0", "foo.txt"),
                                    "foo")),
                             "Found null intermediate value when trying to map")
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
