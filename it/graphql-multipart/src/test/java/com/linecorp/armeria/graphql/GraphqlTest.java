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

package com.linecorp.armeria.graphql;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.google.common.io.Files;

import com.linecorp.armeria.common.graphql.scalar.MoreScalars;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.graphql.GraphqlService;
import com.linecorp.armeria.server.graphql.GraphqlServiceContexts;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.com.google.common.collect.ImmutableList;
import graphql.schema.DataFetcher;

class GraphqlTest {

    private final RestTemplate restTemplate = new RestTemplate();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile = new File(ClassLoader.getSystemResource(
                    "testing/graphql/multipart/test.graphqls").toURI());
            final GraphqlService service =
                    GraphqlService.builder()
                                  .schemaFile(graphqlSchemaFile)
                                  .runtimeWiring(c -> {
                                      c.scalar(MoreScalars.multipartFile());
                                      c.type("Mutation",
                                             typeWiring -> typeWiring.dataFetcher("fileUpload",
                                                                                  fileUploadFetcher()));
                                      c.type("Mutation",
                                             typeWiring -> typeWiring.dataFetcher("fileUploads",
                                                                                  fileUploadsFetcher()));
                                  })
                                  .build();
            sb.service("/graphql", service);
        }
    };

    private static DataFetcher<CompletableFuture<String>> fileUploadFetcher() {
        return environment -> CompletableFuture.supplyAsync(() -> {
            try {
                final com.linecorp.armeria.common.multipart.MultipartFile multipartFile =
                        environment.getArgument("file");
                return Files.asCharSource(multipartFile.file(), StandardCharsets.UTF_8).read();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, GraphqlServiceContexts.get(environment).blockingTaskExecutor());
    }

    private static DataFetcher<CompletableFuture<List<String>>> fileUploadsFetcher() {
        return environment -> CompletableFuture.supplyAsync(() -> {
            final List<com.linecorp.armeria.common.multipart.MultipartFile> multipartFiles =
                    environment.getArgument("files");
            return multipartFiles.stream().map(it -> {
                try {
                    return Files.asCharSource(it.file(), StandardCharsets.UTF_8).read();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).collect(toImmutableList());
        }, GraphqlServiceContexts.get(environment).blockingTaskExecutor());
    }

    @Test
    void multipartSingleFile() {
        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        final String query = "mutation FileUpload($file: MultipartFile) {fileUpload(file: $file)}";
        final String variables = "{\"file\": null}";
        body.put("operations",
                 ImmutableList.of(String.format("{ \"query\": \"%s\", \"variables\": %s}", query, variables)));
        body.put("map", ImmutableList.of("{\"0\": [\"variables.file\"]}"));
        final MultipartFile multipart =
                new MockMultipartFile("0", "dummy.txt", "plain/txt", "Hello!".getBytes(StandardCharsets.UTF_8));
        body.put("0", ImmutableList.of(multipart.getResource()));

        final ResponseEntity<String> response =
                restTemplate.postForEntity(server.httpUri().resolve("/graphql"), body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"data\":{\"fileUpload\":\"Hello!\"}}");
    }

    @Test
    void multipartFileList() {
        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        final String query = "mutation FileUploads($files: [MultipartFile]) {fileUploads(files: $files)}";
        final String variables = "{\"files\": [null]}";
        body.put("operations",
                 ImmutableList.of(String.format("{ \"query\": \"%s\", \"variables\": %s}", query, variables)));
        body.put("map", ImmutableList.of("{\"0\": [\"variables.files.0\"]}"));
        final MultipartFile multipart =
                new MockMultipartFile("0", "dummy.txt", "plain/txt", "Hello!".getBytes(StandardCharsets.UTF_8));
        body.put("0", ImmutableList.of(multipart.getResource()));

        final ResponseEntity<String> response =
                restTemplate.postForEntity(server.httpUri().resolve("/graphql"), body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"data\":{\"fileUploads\":[\"Hello!\"]}}");
    }
}
