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
package com.linecorp.armeria.server.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.io.Files;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.scalar.PathScalar;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.com.google.common.collect.ImmutableList;
import graphql.schema.DataFetcher;
import graphql.schema.idl.errors.SchemaProblem;

class GraphqlServiceMultipartTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final File graphqlSchemaFile =
                    new File(getClass().getResource("/multipart.graphqls").toURI());
            final GraphqlService service =
                    GraphqlService.builder()
                                  .schemaFile(graphqlSchemaFile)
                                  .runtimeWiring(c -> {
                                      c.scalar(PathScalar.of());
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
        return environment -> {
            final Path path = environment.getArgument("path");
            return Files.asCharSource(path.toFile(), StandardCharsets.UTF_8).read();
        };
    }

    private static DataFetcher<List<String>> fileUploadsFetcher() {
        return environment -> {
            final List<Path> paths = environment.getArgument("path");
            return paths.stream()
                    .map(path -> {
                        try {
                            return Files.asCharSource(path.toFile(), StandardCharsets.UTF_8).read();
                        } catch (IOException ignored) {
                            return null;
                        }
                    })
                    .collect(ImmutableList.toImmutableList());
        };
    }

    @Test
    void multipartSingleFile() {
        final String query = "mutation FileUpload($file: Path) {fileUpload(path: $file)}";
        final String variables = "{\"file\": null}";
        final HttpRequest request = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "operations"),
                            String.format("{ \"query\": \"%s\", \"variables\": %s}", query, variables)),
                BodyPart.of(ContentDisposition.of("form-data", "map"),
                            "{\"0\": [\"variables.file\"]}"),
                BodyPart.of(ContentDisposition.of("form-data", "0", "test.txt"),
                            "Hello!")
        ).toHttpRequest("/graphql");

        final BlockingWebClient client = server.webClient().blocking();
        final AggregatedHttpResponse response = client.execute(request);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("{\"data\":{\"fileUpload\":\"Hello!\"}}");
    }

    @Test
    void multipartFileList() {
        final String query = "mutation FileUploads($files: [Path]) {fileUploads(path: $files)}";
        final String variables = "{\"files\": [null]}";
        final HttpRequest request = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "operations"),
                            String.format("{ \"query\": \"%s\", \"variables\": %s}", query, variables)),
                BodyPart.of(ContentDisposition.of("form-data", "map"),
                            "{\"0\": [\"variables.files.0\"]}"),
                BodyPart.of(ContentDisposition.of("form-data", "0", "test.txt"),
                            "Hello!")
        ).toHttpRequest("/graphql");

        final BlockingWebClient client = server.webClient().blocking();
        final AggregatedHttpResponse response = client.execute(request);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("{\"data\":{\"fileUploads\":[\"Hello!\"]}}");
    }

    @Test
    void shouldCreateGraphqlServiceWithoutPathScalar() throws Exception {
        final File graphqlSchemaFile =
                new File(getClass().getResource("/multipart.graphqls").toURI());

        assertThatThrownBy(() -> {
            GraphqlService.builder()
                          .schemaFile(graphqlSchemaFile)
                          .build();
        }).isInstanceOf(SchemaProblem.class)
          .hasMessageContaining("There is no scalar implementation for the named  'Path' scalar type");
    }
}
