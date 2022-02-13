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

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class AnnotatedServiceMultipartTest {
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/", new MyAnnotatedService());
        }
    };

    @Consumes("multipart/form-data")
    public static class MyAnnotatedService {
        @Post
        @Path("/uploadWithFileParam")
        public HttpResponse uploadWithFileParam(@Param File file1, @Param java.nio.file.Path path1,
                                                @Param String param1) throws IOException {
            return HttpResponse.from(CompletableFuture.supplyAsync(() -> {
                try {
                    final String file1Content = Files.asCharSource(file1, StandardCharsets.UTF_8).read();
                    final String path1Content = Files.asCharSource(path1.toFile(), StandardCharsets.UTF_8)
                                                     .read();
                    return file1Content + '\n' + path1Content;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, ServiceRequestContext.current().blockingTaskExecutor()).thenApply(
                    fileContent -> HttpResponse.of(fileContent + '\n' + param1)));
        }

        @Post
        @Path("/uploadWithMultipartObject")
        public HttpResponse uploadWithMultipartObject(Multipart multipart) {
            return HttpResponse.from(
                    multipart.aggregate()
                             .thenApply(aggregated -> ImmutableList
                                     .of(requireNonNull(aggregated.field("file1")),
                                         requireNonNull(aggregated.field("path1")),
                                         requireNonNull(aggregated.field("param1")))
                                     .stream()
                                     .map(AggregatedHttpObject::contentUtf8)
                                     .collect(Collectors.joining("\n")))
                             .thenApply(HttpResponse::of)
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "/uploadWithFileParam", "/uploadWithMultipartObject" })
    void testUploadFile(String path) throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "file1", "foo.txt"), "foo"),
                BodyPart.of(ContentDisposition.of("form-data", "path1", "bar.txt"), "bar"),
                BodyPart.of(ContentDisposition.of("form-data", "param1"), "armeria")

        );
        final HttpResponse execute = client.execute(multipart.toHttpRequest(path));
        assertThat(execute.aggregate().get().contentUtf8()).isEqualTo("foo\nbar\narmeria");
    }
}
