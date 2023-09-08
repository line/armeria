/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

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
import com.linecorp.armeria.common.multipart.AggregatedBodyPart;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.multipart.MultipartFile;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceMultipartTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/", new MyAnnotatedService());
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @ParameterizedTest
    @ValueSource(strings = { "/uploadWithFileParam", "/uploadWithMultipartObject" })
    void testUploadFile(String path) throws Exception {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "file1", "foo.txt"), "foo"),
                BodyPart.of(ContentDisposition.of("form-data", "path1", "bar.txt"), "bar"),
                BodyPart.of(ContentDisposition.of("form-data", "multipartFile1", "qux.txt"), "qux"),
                BodyPart.of(ContentDisposition.of("form-data", "multipartFile2", "quz.txt"),
                            MediaType.PLAIN_TEXT, "quz"),
                BodyPart.of(ContentDisposition.of("form-data", "param1"), "armeria")

        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest(path));
        assertThatJson(response.contentUtf8())
                .isEqualTo("{\"file1\":\"foo\"," +
                           "\"path1\":\"bar\"," +
                           "\"multipartFile1\":\"qux.txt_qux (application/octet-stream)\"," +
                           "\"multipartFile2\":\"quz.txt_quz (text/plain)\"," +
                           "\"param1\":\"armeria\"}");
    }

    @Test
    void emptyBodyPart() {
        final Multipart multipart = Multipart.of();
        final HttpRequest request = multipart.toHttpRequest("/uploadWithMultipartObject");
        final AggregatedHttpResponse response = server.blockingWebClient().execute(request);
        assertThat(response.contentUtf8()).isEqualTo("{}");
    }

    @Test
    void emptyBodyPart2() {
        final String boundary = "ArmeriaBoundary";
        final MediaType contentType = MediaType.MULTIPART_FORM_DATA.withParameter("boundary", boundary);
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, "/uploadWithMultipartObject")
                                                     .contentType(contentType)
                                                     .build();
        final HttpRequest request =
                HttpRequest.of(headers, HttpData.ofUtf8(
                        "--" + boundary + "--\n" +
                        "content-disposition:form-data; name=\"file1\"; filename=\"foo.txt\"\n" +
                        "content-type:application/octet-stream\n" +
                        '\n' +
                        "foo\n"));
        final AggregatedHttpResponse response = server.blockingWebClient().execute(request);
        assertThat(response.contentUtf8()).isEqualTo("{}");
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
        final AggregatedHttpResponse response = server.blockingWebClient().execute(request);
        assertThat(response.headers().status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains("No closing MIME boundary");
    }

    @Consumes(MediaTypeNames.MULTIPART_FORM_DATA)
    private static class MyAnnotatedService {
        @Blocking
        @Post
        @Path("/uploadWithFileParam")
        public HttpResponse uploadWithFileParam(@Param File file1, @Param java.nio.file.Path path1,
                                                MultipartFile multipartFile1,
                                                @Param MultipartFile multipartFile2,
                                                @Param String param1) throws IOException {
            final String file1Content = Files.asCharSource(file1, StandardCharsets.UTF_8).read();
            final String path1Content = Files.asCharSource(path1.toFile(), StandardCharsets.UTF_8)
                                             .read();
            final MediaType multipartFile1ContentType = multipartFile1.headers().contentType();
            final String multipartFile1Content =
                    Files.asCharSource(multipartFile1.file(), StandardCharsets.UTF_8)
                         .read();
            final MediaType multipartFile2ContentType = multipartFile2.headers().contentType();
            final String multipartFile2Content =
                    Files.asCharSource(multipartFile2.file(), StandardCharsets.UTF_8)
                         .read();
            final ImmutableMap<String, String> content =
                    ImmutableMap.of("file1", file1Content,
                                    "path1", path1Content,
                                    "multipartFile1",
                                    multipartFile1.filename() + '_' + multipartFile1Content +
                                    " (" + multipartFile1ContentType + ')',
                                    "multipartFile2",
                                    multipartFile2.filename() + '_' + multipartFile2Content +
                                    " (" + multipartFile2ContentType + ')',
                                    "param1", param1);
            return HttpResponse.ofJson(content);
        }

        @Post
        @Path("/uploadWithMultipartObject")
        public HttpResponse uploadWithMultipartObject(Multipart multipart) {
            return HttpResponse.of(multipart.aggregate().handle((aggregated, cause) -> {
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
                                content = bodyPart.filename() + '_' + content +
                                          " (" + bodyPart.headers().contentType() + ')';
                            }
                            return content;
                        }));
                return HttpResponse.ofJson(body);
            }));
        }
    }
}
