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
 * under the Licenses
 */

package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MultipartCollectIntegrationTest {

    @TempDir
    static Path tempDir;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/multipart/file", (ctx, req) -> HttpResponse.from(
                      Multipart.from(req).collect(bodyPart -> {
                                   if (bodyPart.filename() != null) {
                                       final Path path = tempDir.resolve(bodyPart.name());
                                       return bodyPart.writeTo(path)
                                                      .thenApply(ignore -> Maps.immutableEntry(bodyPart.name(), path));
                                   }
                                   return bodyPart.aggregate().thenApply(
                                           aggregatedBodyPart -> Maps.immutableEntry(bodyPart.name(),
                                                                                     aggregatedBodyPart.contentUtf8()));
                               })
                               .thenApply(aggregated -> aggregated.stream().collect(
                                       Collectors.toMap(Entry::getKey, Entry::getValue)))
                               .thenApply(aggregated -> {
                                   final StringBuilder responseStringBuilder = new StringBuilder();
                                   responseStringBuilder.append("param1/")
                                                        .append(aggregated.get("param1")).append('\n');
                                   responseStringBuilder.append("param2/")
                                                        .append(aggregated.get("param2")).append('\n');
                                   responseStringBuilder.append("param3/")
                                                        .append(aggregated.get("param3")).append('\n');
                                   try {
                                       final List<String> file1Content =
                                               Files.readLines(((Path) aggregated.get("file1")).toFile(),
                                                               StandardCharsets.UTF_8);
                                       responseStringBuilder
                                               .append("file1/")
                                               .append(file1Content)
                                               .append('\n');
                                       final List<String> file2Content =
                                               Files.readLines(((Path) aggregated.get("file2")).toFile(),
                                                               StandardCharsets.UTF_8);
                                       responseStringBuilder
                                               .append("file2/")
                                               .append(file2Content)
                                               .append('\n');
                                       final List<String> file3Content =
                                               Files.readLines(((Path) aggregated.get("file3")).toFile(),
                                                               StandardCharsets.UTF_8);
                                       responseStringBuilder
                                               .append("file3/")
                                               .append(file3Content);
                                       return HttpResponse.of(responseStringBuilder.toString());
                                   } catch (IOException e) {
                                       throw new UncheckedIOException(e);
                                   }
                               })))
              .service("/multipart/large-file", (ctx, req) -> HttpResponse.from(
                      Multipart.from(req).collect(bodyPart -> {
                                   final Path path = tempDir.resolve(bodyPart.name());
                                   return bodyPart.writeTo(path)
                                                  .thenApply(ignore -> Maps.immutableEntry(bodyPart.name(), path));
                               })
                               .thenApply(aggregated -> aggregated.stream().collect(
                                       Collectors.toMap(Entry::getKey, Entry::getValue)))
                               .thenApply(aggregated -> {
                                   final StringBuilder responseStringBuilder = new StringBuilder();
                                   try {
                                       final HashCode file1Hash =
                                               Files.asByteSource(aggregated.get("file1").toFile())
                                                    .hash(Hashing.sha256());
                                       responseStringBuilder
                                               .append("file1/")
                                               .append(file1Hash)
                                               .append('\n');
                                       final HashCode file2Hash =
                                               Files.asByteSource(aggregated.get("file2").toFile())
                                                    .hash(Hashing.sha256());
                                       responseStringBuilder
                                               .append("file2/")
                                               .append(file2Hash);
                                   } catch (Exception e) {
                                       throw new RuntimeException(e);
                                   }
                                   return HttpResponse.of(responseStringBuilder.toString());
                               })))
              .requestTimeout(Duration.ZERO)
              .maxRequestLength(0);
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
        final Resource file1 = new MultipartInputStreamResource(0, 16 * 1024 * 1024);
        final Resource file2 = new MultipartInputStreamResource(3, 32 * 1024 * 1024);

        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file1", file1);
        body.add("file2", file2);
        final HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        // To allow sending large file
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setBufferRequestBody(false);
        final RestTemplate restTemplate = new RestTemplate(requestFactory);
        final ResponseEntity<String> response =
                restTemplate.postForEntity(server.httpUri().resolve("/multipart/large-file"), requestEntity,
                                           String.class);
        assertThat(response.getBody()).isEqualTo(
                "file1/bbece10379bddacb638d637c0db0b16630a050b31e2d406190e48894ac3c32a8\n" +
                "file2/0d825d57ce699f684a6a5d2e297efd9d3ce959bf13b5b889e22813d7b31af526");
    }

    private static class MultipartInputStreamResource extends InputStreamResource {
        private final long fileSize;

        MultipartInputStreamResource(int seed, long fileSize) {
            super(new InputStream() {
                private final Random random = new Random(seed);
                int count;

                @Override
                public int read() throws IOException {
                    if (count >= fileSize) {
                        return -1;
                    }
                    count++;
                    return random.nextInt(256);
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
