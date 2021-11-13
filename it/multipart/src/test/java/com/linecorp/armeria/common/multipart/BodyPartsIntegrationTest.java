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
 * under the Licenses
 */

package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class BodyPartsIntegrationTest {

    @TempDir
    static Path tempDir;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/multipart/file", (ctx, req) -> HttpResponse.from(
                      BodyParts.collect(Multipart.from(req), tempDir::resolve)
                               .thenApply(aggregated -> {
                                   final StringBuilder responseStringBuilder = new StringBuilder();
                                   final QueryParams queryParams = aggregated.queryParams();
                                   responseStringBuilder.append("param1/")
                                                        .append(queryParams.get("param1")).append('\n');
                                   responseStringBuilder.append("param2/")
                                                        .append(queryParams.get("param2")).append('\n');
                                   responseStringBuilder.append("param3/")
                                                        .append(queryParams.get("param3")).append('\n');
                                   final Map<String, List<Path>> files = aggregated.files();
                                   try {
                                       responseStringBuilder
                                               .append("file1/")
                                               .append(Files.readLines(files.get("file1").get(0).toFile(),
                                                                       StandardCharsets.UTF_8))
                                               .append('\n');
                                       responseStringBuilder
                                               .append("file2/")
                                               .append(Files.readLines(files.get("file2").get(0).toFile(),
                                                                       StandardCharsets.UTF_8))
                                               .append('\n');
                                       responseStringBuilder
                                               .append("file3/")
                                               .append(Files.readLines(files.get("file3").get(0).toFile(),
                                                                       StandardCharsets.UTF_8));
                                       return HttpResponse.of(responseStringBuilder.toString());
                                   } catch (IOException e) {
                                       throw new UncheckedIOException(e);
                                   }
                               })))
              .service("/multipart/large-file", (ctx, req) -> HttpResponse.from(
                      BodyParts.collect(Multipart.from(req), tempDir::resolve)
                               .thenApply(aggregated -> {
                                   final Map<String, List<Path>> files = aggregated.files();
                                   final StringBuilder responseStringBuilder = new StringBuilder();
                                   try {
                                       responseStringBuilder
                                               .append("file1/")
                                               .append(Files.asByteSource(files.get("file1").get(0)
                                                                               .toFile())
                                                            .hash(Hashing.sha256()))
                                               .append('\n');
                                       responseStringBuilder
                                               .append("file2/")
                                               .append(Files.asByteSource(files.get("file2").get(0)
                                                                               .toFile())
                                                            .hash(Hashing.sha256()));
                                   } catch (Exception e) {
                                       throw new RuntimeException(e);
                                   }
                                   return HttpResponse.of(responseStringBuilder.toString());
                               })));
        }

    };

    @Test
    void fileUpload() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        final ClassPathResource file = new ClassPathResource("test.txt");
        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file1", file);
        body.add("file2", file);
        body.add("file3", file);
        body.add("param1", "Hello World");
        body.add("param2", "你好 世界\n안녕 세상\nこんにちは世界");
        body.add("param3", "bar");

        final HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        final RestTemplate restTemplate = new RestTemplate();
        final ResponseEntity<String> response =
                restTemplate.postForEntity(server.httpUri().resolve("/multipart/file"), requestEntity,
                                           String.class);
        assertThat(response.getBody())
                .isEqualTo("param1/Hello World\nparam2/你好 世界\n안녕 세상\nこんにちは世界\nparam3/bar\n" +
                           "file1/[Hello!]\nfile2/[Hello!]\nfile3/[Hello!]");
    }

    @Test
    void fileUploadLargeContent() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        final InputStreamResource file1 = new MultipartInputStreamResource(0, 128 * 1024 * 1024);
        final InputStreamResource file2 = new MultipartInputStreamResource(3, 64 * 1024 * 1024);

        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file1", file1);
        body.add("file2", file2);
        final HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        final RestTemplate restTemplate = new RestTemplate();
        final ResponseEntity<String> response =
                restTemplate.postForEntity(server.httpUri().resolve("/multipart/large-file"), requestEntity,
                                           String.class);

        assertThat(response.getBody()).isEqualTo(
                "file1/a93cf61307111272db812bf0aa2309c4562de783bd65ed678c16d8a5472becdf\n"
                + "file2/e794beaa42424e827d69de64ab8bdf14b112f810bd01f3ca96982aa325f51cb6");
    }

    private static class MultipartInputStreamResource extends InputStreamResource {
        private final long fileSize;

        MultipartInputStreamResource(int seed, long fileSize) {
            super(new InputStream() {
                private final Random random = new Random(seed);
                private int count = 0;

                @Override
                public int read() throws IOException {
                    if (count > fileSize) {
                        return -1;
                    }
                    count++;
                    return (byte) random.nextInt();
                }
            });
            this.fileSize = fileSize;
        }

        @Override
        public String getFilename() {
            return fileSize + "-test.txt";
        }

        @Override
        public long contentLength() throws IOException {
            return fileSize;
        }
    }
}
