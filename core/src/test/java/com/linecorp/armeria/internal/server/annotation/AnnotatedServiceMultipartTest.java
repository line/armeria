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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.common.multipart.AggregatedBodyPart;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.multipart.MultipartFile;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceMultipartTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/", new MyAnnotatedService());
        }
    };

    @ParameterizedTest
    @ValueSource(strings = { "/uploadWithFileParam", "/uploadWithMultipartObject" })
    void testUploadFile(String path) throws Exception {
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "file1", "foo.txt"), "foo"),
                BodyPart.of(ContentDisposition.of("form-data", "path1", "bar.txt"), "bar"),
                BodyPart.of(ContentDisposition.of("form-data", "multipartFile1", "qux.txt"), "qux"),
                BodyPart.of(ContentDisposition.of("form-data", "param1"), "armeria")

        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest(path));
        assertThatJson(response.contentUtf8())
                .isEqualTo("{\"file1\":\"foo\"," +
                           "\"path1\":\"bar\"," +
                           "\"multipartFile1\":\"qux.txt_qux\"," +
                           "\"param1\":\"armeria\"}");
    }

    @Consumes(MediaTypeNames.MULTIPART_FORM_DATA)
    private static class MyAnnotatedService {
        @Post
        @Path("/uploadWithFileParam")
        public HttpResponse uploadWithFileParam(@Param File file1, @Param java.nio.file.Path path1,
                                                @Param MultipartFile multipartFile1,
                                                @Param String param1) throws IOException {
            return HttpResponse.from(CompletableFuture.supplyAsync(() -> {
                try {
                    final String file1Content = Files.asCharSource(file1, StandardCharsets.UTF_8).read();
                    final String path1Content = Files.asCharSource(path1.toFile(), StandardCharsets.UTF_8)
                                                     .read();
                    final String multipartFile1Content =
                            Files.asCharSource(multipartFile1.file(), StandardCharsets.UTF_8)
                                 .read();
                    final ImmutableMap<String, String> content =
                            ImmutableMap.of("file1", file1Content,
                                            "path1", path1Content,
                                            "multipartFile1",
                                            multipartFile1.filename() + '_' + multipartFile1Content,
                                            "param1", param1);
                    return HttpResponse.ofJson(content);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, ServiceRequestContext.current().blockingTaskExecutor()));
        }

        @Post
        @Path("/uploadWithMultipartObject")
        public HttpResponse uploadWithMultipartObject(Multipart multipart) {
            return HttpResponse.from(multipart.aggregate().thenApply(aggregated -> {
                final ImmutableMap<String, String> body =
                        aggregated.names().stream().collect(toImmutableMap(Function.identity(), e -> {
                            final AggregatedBodyPart bodyPart =
                                    aggregated.field(e);
                            String content = bodyPart.contentUtf8();
                            if ("multipartFile1".equals(e)) {
                                content = bodyPart.filename() + '_' + content;
                            }
                            return content;
                        }));
                return HttpResponse.ofJson(body);
            }));
        }
    }
}
