/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.server.MultipartRemovalStrategy;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MultipartTempFileRemovalTest {

    private static final Logger logger = LoggerFactory.getLogger(MultipartTempFileRemovalTest.class);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService()
              .pathPrefix("/never")
              .multipartRemovalStrategy(MultipartRemovalStrategy.NEVER)
              .build(new TestMultipartService());
            sb.annotatedService()
              .pathPrefix("/default")
              .build(new TestMultipartService());
        }
    };

    @CsvSource({ "/never", "/default" })
    @ParameterizedTest
    void testRemovalStrategy(String type) throws Exception {
        final BlockingWebClient client = server.blockingWebClient();
        final ContentDisposition contentDisposition = ContentDisposition.of("form-data", "file1", "file1.txt");
        final Multipart multipart = Multipart.of(BodyPart.of(contentDisposition, "file1 content"));
        final AggregatedHttpResponse response = client.execute(multipart.toHttpRequest(type + "/upload"));
        server.requestContextCaptor().take().log().whenComplete().join();
        final java.nio.file.Path path = Paths.get(response.contentUtf8());
        if ("/never".equals(type)) {
            assertThat(Files.exists(path)).isTrue();
            Files.delete(path);
        } else {
            await().untilAsserted(() -> assertThat(Files.exists(path)).isFalse());
        }
    }

    @Consumes(MediaTypeNames.MULTIPART_FORM_DATA)
    private static class TestMultipartService {
        @Blocking
        @Post
        @Path("/upload")
        public HttpResponse upload(@Param File file1) throws IOException {
            return HttpResponse.of(file1.getPath());
        }
    }
}
