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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.server.graphql.GraphqlServiceContexts;
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
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.com.google.common.collect.ImmutableList;
import graphql.schema.DataFetcher;

class GraphQLTest {

    private final RestTemplate restTemplate = new RestTemplate();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile = new File(ClassLoader.getSystemResource("test.graphqls").toURI());
            final GraphqlService service =
                    GraphqlService.builder()
                                  .schemaFile(graphqlSchemaFile)
                                  .runtimeWiring(c -> {
                                      c.scalar(MoreScalars.path());
                                      final DataFetcher<String> fileUpload = fileUploadFetcher();
                                      c.type("Mutation",
                                             typeWiring -> typeWiring.dataFetcher("fileUpload", fileUpload));
                                      final DataFetcher<List<String>> fileUploads = fileUploadsFetcher();
                                      c.type("Mutation",
                                             typeWiring -> typeWiring.dataFetcher("fileUploads", fileUploads));
                                  })
                                  .build();
            sb.service("/graphql", service);
        }
    };

    private static DataFetcher<String> fileUploadFetcher() {
        return environment -> CompletableFuture.supplyAsync(() -> {
            try {
                final Path path = environment.getArgument("path");
                return Files.asCharSource(path.toFile(), StandardCharsets.UTF_8).read();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, GraphqlServiceContexts.get(environment).blockingTaskExecutor()).join();
    }

    private static DataFetcher<List<String>> fileUploadsFetcher() {
        return environment -> CompletableFuture.supplyAsync(() -> {
            final List<Path> paths = environment.getArgument("path");
            return paths.stream()
                        .map(path -> {
                            try {
                                return Files.asCharSource(path.toFile(), StandardCharsets.UTF_8).read();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                        .collect(toImmutableList());
        }, GraphqlServiceContexts.get(environment).blockingTaskExecutor()).join();
    }

    @Test
    void multipartSingleFile() {
        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        final String query = "mutation FileUpload($file: Path) {fileUpload(path: $file)}";
        final String variables = "{\"file\": null}";
        body.put("operations", ImmutableList.of(String.format("{ \"query\": \"%s\", \"variables\": %s}",
                                                              query, variables)));
        body.put("map", ImmutableList.of("{\"0\": [\"variables.file\"]}"));
        final MultipartFile multipart = new MockMultipartFile("0", "dummy.txt", "plain/txt",
                                                              "Hello!".getBytes(StandardCharsets.UTF_8));
        body.put("0", ImmutableList.of(multipart.getResource()));

        final ResponseEntity<String> response = restTemplate.postForEntity(server.httpUri().resolve("/graphql"),
                                                                           body,
                                                                           String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"data\":{\"fileUpload\":\"Hello!\"}}");
    }

    @Test
    void multipartFileList() {
        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        final String query = "mutation FileUploads($files: [Path]) {fileUploads(path: $files)}";
        final String variables = "{\"files\": [null]}";
        body.put("operations", ImmutableList.of(String.format("{ \"query\": \"%s\", \"variables\": %s}",
                                                              query, variables)));
        body.put("map", ImmutableList.of("{\"0\": [\"variables.files.0\"]}"));
        final MultipartFile multipart = new MockMultipartFile("0", "dummy.txt", "plain/txt",
                                                              "Hello!".getBytes(StandardCharsets.UTF_8));
        body.put("0", ImmutableList.of(multipart.getResource()));

        final ResponseEntity<String> response = restTemplate.postForEntity(server.httpUri().resolve("/graphql"),
                                                                           body,
                                                                           String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"data\":{\"fileUploads\":[\"Hello!\"]}}");
    }
}
