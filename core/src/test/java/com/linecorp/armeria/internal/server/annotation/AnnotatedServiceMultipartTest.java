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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableList;
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
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.multipart.AggregatedBodyPart;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.multipart.MultipartFile;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Part;
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
    void testUploadMultipleFilesWithSameNameMultipart() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "files", "bar.txt"), "bar"),
                BodyPart.of(ContentDisposition.of("form-data", "files", "qux.txt"), "qux"),
                BodyPart.of(ContentDisposition.of("form-data", "files", "quz.txt"), MediaType.PLAIN_TEXT,
                            "quz"),
                BodyPart.of(ContentDisposition.of("form-data", "params"), "hello"),
                BodyPart.of(ContentDisposition.of("form-data", "params"), "armeria")
        );

        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(
                        multipart.toHttpRequest("/uploadWithFileParamSameNameMultipart")
                );

        final ImmutableMap<String, List<String>> expected = ImmutableMap.of(
                "files", ImmutableList.of("fileName bar.txt data bar contentType application/octet-stream",
                                          "fileName qux.txt data qux contentType application/octet-stream",
                                          "fileName quz.txt data quz contentType text/plain"),
                "params", ImmutableList.of("hello",
                                           "armeria"
                )
        );
        assertThatJson(response.contentUtf8()).isEqualTo(expected);
    }

    @Test
    void testUploadMultipleFilesWithSameNamePath() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "files", "bar.txt"), "bar"),
                BodyPart.of(ContentDisposition.of("form-data", "files", "qux.txt"), "qux"),
                BodyPart.of(ContentDisposition.of("form-data", "files", "quz.txt"), MediaType.PLAIN_TEXT,
                            "quz"),
                BodyPart.of(ContentDisposition.of("form-data", "params"), "hello"),
                BodyPart.of(ContentDisposition.of("form-data", "params"), "armeria")
        );

        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(
                        multipart.toHttpRequest("/uploadWithFileParamSameNamePath")
                );

        final ImmutableMap<String, List<String>> expected = ImmutableMap.of(
                "files", ImmutableList.of("data bar", "data qux", "data quz"),
                "params", ImmutableList.of("hello", "armeria"
                )
        );
        assertThatJson(response.contentUtf8()).isEqualTo(expected);
    }

    @Test
    void testUploadMultipleFilesWithSameNameFile() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "files", "bar.txt"), "bar"),
                BodyPart.of(ContentDisposition.of("form-data", "files", "qux.txt"), "qux"),
                BodyPart.of(ContentDisposition.of("form-data", "files", "quz.txt"), MediaType.PLAIN_TEXT,
                            "quz"),
                BodyPart.of(ContentDisposition.of("form-data", "params"), "hello"),
                BodyPart.of(ContentDisposition.of("form-data", "params"), "armeria")
        );

        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(
                        multipart.toHttpRequest("/uploadWithFileParamSameNameFile")
                );

        final ImmutableMap<String, List<String>> expected = ImmutableMap.of(
                "files", ImmutableList.of("data bar", "data qux", "data quz"),
                "params", ImmutableList.of("hello", "armeria")
        );
        assertThatJson(response.contentUtf8()).isEqualTo(expected);
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

    @Test
    void testPartWithJsonBlob() {
        // Simulates FormData.append("data", new Blob([JSON], { type: "application/json" }))
        // which results in filename="blob" and Content-Type: application/json
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "data", "blob"),
                            MediaType.JSON, "{\"name\":\"test\",\"value\":42}")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partJson"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("{\"name\":\"test\",\"value\":42}");
    }

    @Test
    void testPartWithJsonNoFilename_noContentType() {
        // FormData.append("data", JSON.stringify(...)) — no filename, no content-type
        // Should fail because Content-Type is not application/json.
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "data"),
                            "{\"name\":\"nofile\",\"value\":7}")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partJson"));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testPartWithJsonNoFilename_withContentType() {
        // Part without filename but with explicit Content-Type: application/json
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "data"),
                            MediaType.JSON, "{\"name\":\"nofile\",\"value\":7}")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partJson"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("{\"name\":\"nofile\",\"value\":7}");
    }

    @Test
    void testPartMixedWithParam() {
        // JSON part + regular file upload + string param in the same request
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "data", "blob"),
                            MediaType.JSON, "{\"name\":\"mixed\",\"value\":123}"),
                BodyPart.of(ContentDisposition.of("form-data", "file1", "test.txt"), "file-content"),
                BodyPart.of(ContentDisposition.of("form-data", "title"), "my-title")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partMixed"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("{\"name\":\"mixed\",\"value\":123," +
                           "\"file\":\"file-content\",\"title\":\"my-title\"}");
    }

    @Test
    void testPartWithString() {
        // @Part String should return raw content
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "text"), "hello world")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partString"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("hello world");
    }

    @Test
    void testPartWithMultipartFile() {
        // @Part MultipartFile should work like @Param MultipartFile
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "file1", "doc.txt"), "doc-content")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partFile"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("doc.txt:doc-content");
    }

    @Test
    void testPartWithFileAndPath() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "file1", "foo.txt"), "foo-content"),
                BodyPart.of(ContentDisposition.of("form-data", "path1", "bar.txt"), "bar-content")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partFileAndPath"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("{\"file\":\"foo-content\",\"path\":\"bar-content\"}");
    }

    @Test
    void testPartWithListOfBeans() {
        // Multiple JSON parts with the same name → List<MyData>
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "items"),
                            MediaType.JSON, "{\"name\":\"a\",\"value\":1}"),
                BodyPart.of(ContentDisposition.of("form-data", "items"),
                            MediaType.JSON, "{\"name\":\"b\",\"value\":2}"),
                BodyPart.of(ContentDisposition.of("form-data", "items"),
                            MediaType.JSON, "{\"name\":\"c\",\"value\":3}")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partList"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("[{\"name\":\"a\",\"value\":1}," +
                           "{\"name\":\"b\",\"value\":2}," +
                           "{\"name\":\"c\",\"value\":3}]");
    }

    @Test
    void testPartWithBytes() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "data"), "binary-content")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partBytes"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("14");
    }

    @Test
    void testPartWithBytesAndFilename() {
        // @Part byte[] on a part with filename — should still work via in-memory aggregation
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "data", "blob"), "blob-content")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partBytes"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("12");
    }

    @Test
    void testPartWithNullable() {
        // @Part @Nullable MyData — part not sent, should return null
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "other"), "something")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partNullable"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("null");
    }

    @Test
    void testPartWithNullable_present() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "data"),
                            MediaType.JSON, "{\"name\":\"present\",\"value\":1}")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partNullable"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("{\"name\":\"present\",\"value\":1}");
    }

    @Test
    void testPartWithSetOfBeans() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "items"),
                            MediaType.JSON, "{\"name\":\"x\",\"value\":1}"),
                BodyPart.of(ContentDisposition.of("form-data", "items"),
                            MediaType.JSON, "{\"name\":\"y\",\"value\":2}")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partSet"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("[{\"name\":\"x\",\"value\":1}," +
                           "{\"name\":\"y\",\"value\":2}]");
    }

    @Test
    void testPartWithListOfStrings() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "items"), "hello"),
                BodyPart.of(ContentDisposition.of("form-data", "items"), "world"),
                BodyPart.of(ContentDisposition.of("form-data", "items"), "armeria")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partListString"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("[\"hello\",\"world\",\"armeria\"]");
    }

    @Test
    void testPartWithListOfFiles() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "files", "a.txt"), "aaa"),
                BodyPart.of(ContentDisposition.of("form-data", "files", "b.txt"), "bbb")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partListFile"));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("[\"a.txt:aaa\",\"b.txt:bbb\"]");
    }

    @Test
    void testPartWithUnsupportedContentType() {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "data"),
                            MediaType.XML_UTF_8, "<data>value</data>")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/partJson"));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
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

        @Blocking
        @Post
        @Path("/uploadWithFileParamSameNameMultipart")
        public HttpResponse uploadWithFileParamSameNameMultipart(
                @Param("params") List<String> params,
                @Param("files") List<MultipartFile> files) {
            final List<String> fileData = files
                    .stream()
                    .map(multipartFile -> {
                        try {
                            final String data = Files.asCharSource(multipartFile.file(),
                                                                   StandardCharsets.UTF_8)
                                                     .read();
                            return String.format("fileName %s data %s contentType %s",
                                                 multipartFile.filename(), data,
                                                 multipartFile.headers().contentType());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).collect(toImmutableList());
            final ImmutableMap<String, List<String>> content =
                    ImmutableMap.of("files", fileData,
                                    "params", params);
            return HttpResponse.ofJson(content);
        }

        @Blocking
        @Post
        @Path("/uploadWithFileParamSameNamePath")
        public HttpResponse uploadWithFileParamSameNamePath(
                @Param("params") List<String> params,
                @Param("files") List<java.nio.file.Path> files) {
            final List<String> fileData = files
                    .stream()
                    .map(path -> {
                        try {
                            final String data = Files.asCharSource(path.toFile(), StandardCharsets.UTF_8)
                                                     .read();
                            return String.format("data %s", data);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).collect(toImmutableList());
            final ImmutableMap<String, List<String>> content =
                    ImmutableMap.of("files", fileData,
                                    "params", params);
            return HttpResponse.ofJson(content);
        }

        @Blocking
        @Post
        @Path("/uploadWithFileParamSameNameFile")
        public HttpResponse uploadWithFileParamSameNameFile(
                @Param("params") List<String> params,
                @Param("files") List<File> files,
                RequestHeaders headers) {
            final List<String> fileData = files
                    .stream()
                    .map(file -> {
                        try {
                            final String data = Files.asCharSource(file, StandardCharsets.UTF_8)
                                                     .read();
                            return String.format("data %s", data);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).collect(toImmutableList());
            final ImmutableMap<String, List<String>> content =
                    ImmutableMap.of("files", fileData,
                                    "params", params);
            return HttpResponse.ofJson(content);
        }

        @Blocking
        @Post
        @Path("/partJson")
        public HttpResponse partJson(@Part MyData data) {
            return HttpResponse.ofJson(ImmutableMap.of("name", data.name, "value", data.value));
        }

        @Blocking
        @Post
        @Path("/partMixed")
        public HttpResponse partMixed(@Part MyData data,
                                      @Part MultipartFile file1,
                                      @Param String title) throws IOException {
            final String fileContent = Files.asCharSource(file1.file(), StandardCharsets.UTF_8).read();
            return HttpResponse.ofJson(ImmutableMap.of("name", data.name, "value", data.value,
                                                       "file", fileContent, "title", title));
        }

        @Blocking
        @Post
        @Path("/partString")
        public HttpResponse partString(@Part String text) {
            return HttpResponse.of(text);
        }

        @Blocking
        @Post
        @Path("/partFile")
        public HttpResponse partFile(@Part MultipartFile file1) throws IOException {
            final String content = Files.asCharSource(file1.file(), StandardCharsets.UTF_8).read();
            return HttpResponse.of(file1.filename() + ':' + content);
        }

        @Blocking
        @Post
        @Path("/partFileAndPath")
        public HttpResponse partFileAndPath(@Part File file1,
                                            @Part java.nio.file.Path path1) throws IOException {
            final String fileContent = Files.asCharSource(file1, StandardCharsets.UTF_8).read();
            final String pathContent = Files.asCharSource(path1.toFile(), StandardCharsets.UTF_8).read();
            return HttpResponse.ofJson(ImmutableMap.of("file", fileContent, "path", pathContent));
        }

        @Blocking
        @Post
        @Path("/partList")
        public HttpResponse partList(@Part List<MyData> items) {
            final List<ImmutableMap<String, Object>> result = items.stream()
                    .map(d -> ImmutableMap.<String, Object>of("name", d.name, "value", d.value))
                    .collect(toImmutableList());
            return HttpResponse.ofJson(result);
        }

        @Blocking
        @Post
        @Path("/partBytes")
        public HttpResponse partBytes(@Part byte[] data) {
            return HttpResponse.of(String.valueOf(data.length));
        }

        @Blocking
        @Post
        @Path("/partNullable")
        public HttpResponse partNullable(@Part @Nullable MyData data) {
            if (data == null) {
                return HttpResponse.of("null");
            }
            return HttpResponse.ofJson(ImmutableMap.of("name", data.name, "value", data.value));
        }

        @Blocking
        @Post
        @Path("/partSet")
        public HttpResponse partSet(@Part Set<MyData> items) {
            final List<ImmutableMap<String, Object>> result = items.stream()
                    .map(d -> ImmutableMap.<String, Object>of("name", d.name, "value", d.value))
                    .collect(toImmutableList());
            return HttpResponse.ofJson(result);
        }

        @Blocking
        @Post
        @Path("/partListString")
        public HttpResponse partListString(@Part List<String> items) {
            return HttpResponse.ofJson(items);
        }

        @Blocking
        @Post
        @Path("/partListFile")
        public HttpResponse partListFile(@Part List<MultipartFile> files) {
            final List<String> result = files.stream()
                    .map(f -> {
                        try {
                            return f.filename() + ':' +
                                   Files.asCharSource(f.file(), StandardCharsets.UTF_8).read();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).collect(toImmutableList());
            return HttpResponse.ofJson(result);
        }
    }

    static class MyData {
        private String name;
        private int value;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }
}
