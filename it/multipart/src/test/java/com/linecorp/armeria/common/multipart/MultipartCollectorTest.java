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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MultipartCollectorTest {

    @TempDir
    static Path tempDir;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/multipart/file", (ctx, req) -> HttpResponse.from(
                    BodyParts.collect(Multipart.from(req), tempDir::resolve)
                             .thenApply(aggregated -> {
                                 final StringBuilder stringBuilder = new StringBuilder();
                                 final QueryParams queryParams = aggregated.getQueryParams();
                                 stringBuilder.append("param1/")
                                              .append(queryParams.get("param1")).append('\n');
                                 stringBuilder.append("param2/")
                                              .append(queryParams.get("param2")).append('\n');
                                 stringBuilder.append("param3/")
                                              .append(queryParams.get("param3")).append('\n');
                                 final Map<String, List<Path>> files = aggregated.getFiles();
                                 System.out.println(files);
                                 try {
                                     stringBuilder.append("file1/")
                                                  .append(Files.readAllLines(files.get("file1").get(0)))
                                                  .append('\n');
                                     stringBuilder.append("file2/")
                                                  .append(Files.readAllLines(files.get("file2").get(0)))
                                                  .append('\n');
                                     stringBuilder.append("file3/")
                                                  .append(Files.readAllLines(files.get("file3").get(0)));
                                     return HttpResponse.of(stringBuilder.toString());
                                 } catch (IOException e) {
                                     throw new UncheckedIOException(e);
                                 }
                             })));
        }
    };

    @Test
    void multipartFile() {
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
}
