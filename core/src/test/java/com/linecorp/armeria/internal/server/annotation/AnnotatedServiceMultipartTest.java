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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.io.Files;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.server.ServerBuilder;
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

    public static class MyAnnotatedService {
        @Post
        @Path("/uploadWithFileParam")
        public HttpResponse returnInt(@Param File file1) throws IOException {
            return HttpResponse.of(Files.asCharSource(file1, StandardCharsets.UTF_8).read());
        }

        @Post
        @Path("/uploadWithPathParam")
        public HttpResponse returnInt(@Param java.nio.file.Path file1) throws IOException {
            //noinspection UnstableApiUsage
            return HttpResponse.of(Files.asCharSource(file1.toFile(), StandardCharsets.UTF_8).read());
        }

        @Post
        @Path("/uploadWithMultipartObject")
        public HttpResponse returnInt(Multipart multipart) {
            return HttpResponse.from(
                    multipart.aggregate()
                             .thenApply(aggregatedMultipart -> aggregatedMultipart.field("file1"))
                             .thenApply(AggregatedHttpObject::contentUtf8)
                             .thenApply(HttpResponse::of)
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "/uploadWithFileParam", "/uploadWithPathParam", "/uploadWithMultipartObject" })
    void testUploadFile(String path) throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final BodyPart part1 = BodyPart.of(ContentDisposition.of("form-data", "file1", "foo.txt"), "Armeria");
        final Multipart multipart = Multipart.of(part1);
        final HttpResponse execute = client.execute(multipart.toHttpRequest(path));
        assertThat(execute.aggregate().get().contentUtf8()).isEqualTo("Armeria");
    }
}
