/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.testing.junit5.client;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.multipart.AggregatedBodyPart;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class WebTestClientTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/", new MyAnnotatedService());
        }
    };

    @Test
    void restApi() {
        final WebTestClientRequestPreparation preparation = server.webTestClient().prepare();
        // HTTP methods used for REST APIs
        // See: https://restfulapi.net/http-methods/
        for (HttpMethod method : ImmutableList.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                                                  HttpMethod.DELETE, HttpMethod.PATCH)) {
            switch (method) {
                case GET:
                    preparation.get("/rest/{id}");
                    break;
                case POST:
                    preparation.post("/rest/{id}");
                    break;
                case PUT:
                    preparation.put("/rest/{id}");
                    break;
                case PATCH:
                    preparation.patch("/rest/{id}");
                    break;
                case DELETE:
                    preparation.delete("/rest/{id}");
                    break;
            }
            assertNotNull(preparation);
            preparation.content("content-value")
                       .header("x-header", "header-value")
                       .pathParam("id", "1")
                       .queryParam("query", "query-value")
                       .execute()
                       .assertStatus().isOk()
                       .assertHeaders().contains("x-header", "header-value")
                       .assertContent().stringUtf8IsEqualTo("{\"id\":\"1\"," +
                                                            "\"query\":\"query-value\"," +
                                                            "\"content\":\"content-value\"}")
                       .assertTrailers().isEmpty();
        }
    }

    @Test
    void testUploadFile() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "file1", "foo.txt"), "foo"),
                BodyPart.of(ContentDisposition.of("form-data", "path1", "bar.txt"), "bar"),
                BodyPart.of(ContentDisposition.of("form-data", "multipartFile1", "qux.txt"), "qux"),
                BodyPart.of(ContentDisposition.of("form-data", "multipartFile2", "quz.txt"), "quz"),
                BodyPart.of(ContentDisposition.of("form-data", "param1"), "armeria")

        );
        server.webTestClient()
              .execute(multipart.toHttpRequest("/uploadWithMultipartObject"))
              .assertStatus().isOk()
              .assertHeaders().contentLengthIsEqualTo(110)
              .assertHeaders().contentTypeIsEqualTo(MediaType.JSON)
              .assertContent().stringUtf8IsEqualTo("{\"file1\":\"foo\"," +
                                                   "\"path1\":\"bar\"," +
                                                   "\"multipartFile1\":\"qux.txt_qux\"," +
                                                   "\"multipartFile2\":\"quz.txt_quz\"," +
                                                   "\"param1\":\"armeria\"}")
              .assertTrailers().isEmpty();
    }

    @Test
    void missingEndingBoundary() {
        final String boundary = "ArmeriaBoundary";
        final MediaType contentType = MediaType.MULTIPART_FORM_DATA.withParameter("boundary", boundary);
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, "/uploadWithMultipartObject")
                                                     .contentType(contentType)
                                                     .build();
        final HttpRequest request =
                HttpRequest.of(headers, HttpData.ofUtf8(
                        "--" + boundary + '\n' +
                        "content-disposition:form-data; name=\"file1\"; filename=\"foo.txt\"\n" +
                        "content-type:application/octet-stream\n" +
                        '\n' +
                        "foo\n"));
        server.webTestClient().execute(request)
              .assertStatus().isBadRequest()
              .assertContent().stringUtf8Contains("No closing MIME boundary");
    }

    @Test
    void testAbortedResponse() {
        final WebTestClient client = WebTestClient.of();
        client.get("/error")
              .assertCause()
              .isInstanceOf(IllegalArgumentException.class);

        final TestHttpResponse response = TestHttpResponse.ofFailure(
                new ArithmeticException("One plus one is two."));
        response.assertCause()
                .isInstanceOf(ArithmeticException.class)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("One plus one is two.")
                .hasMessageStartingWith("One")
                .hasMessageContaining("plus")
                .hasMessageNotContaining("three")
                .hasMessageEndingWith("two.");
    }

    @Test
    void usingIncorrectAssertionMethod() {
        assertThrows(AssertionError.class, () -> {
            TestHttpResponse.of(AggregatedHttpResponse.of(200)).assertCause();
        }, "Expecting the response to raise a throwable.");

        TestHttpResponse aborted = TestHttpResponse.ofFailure(new RuntimeException("Runtime exception."));
        assertThrows(AssertionError.class, () -> {
            aborted.assertStatus().isOk();
        });
        aborted.assertCause()
               .isInstanceOf(RuntimeException.class)
               .hasMessage("Runtime exception.")
               .hasNoCause();
    }

    private static class MyAnnotatedService {
        @Get
        @Post
        @Put
        @Delete
        @Patch
        @ProducesJson
        @Path("/rest/{id}")
        public HttpResponse restApi(@Param String id, @Param String query,
                                    @Header("x-header") String header, String content,
                                    ServiceRequestContext ctx) {
            return HttpResponse.of(
                    ResponseHeaders.of(HttpStatus.OK, "x-header", header),
                    HttpData.ofUtf8(
                            "{\"id\":\"" + id + "\",\"query\":\"" + query + "\",\"content\":\"" + content +
                            "\"}"));
        }

        @Post
        @Path("/uploadWithMultipartObject")
        @Consumes(MediaTypeNames.MULTIPART_FORM_DATA)
        public HttpResponse uploadWithMultipartObject(Multipart multipart) {
            return HttpResponse.from(multipart.aggregate().handle((aggregated, cause) -> {
                if (cause != null) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                           cause.getMessage());
                }

                final ImmutableMap<String, String> body =
                        aggregated.names().stream().collect(toImmutableMap(Function.identity(), e -> {
                            final AggregatedBodyPart bodyPart =
                                    aggregated.field(e);
                            String content = bodyPart.contentUtf8();
                            if (e.startsWith("multipartFile")) {
                                content = bodyPart.filename() + '_' + content;
                            }
                            return content;
                        }));
                return HttpResponse.ofJson(body);
            }));
        }
    }
}
