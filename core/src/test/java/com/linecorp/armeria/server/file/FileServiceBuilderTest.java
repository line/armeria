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
 * under the License.
 */

package com.linecorp.armeria.server.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class FileServiceBuilderTest {

    private static final String baseResourceDir =
      FileServiceBuilderTest.class.getPackage().getName().replace('.', '/') + '/';

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder(
              "/mimeTypeFunction",
              FileService.builder(getClass().getClassLoader(), baseResourceDir + "baz")
                .serveCompressedFiles(true)
                .autoDecompress(true)
                .maxCacheEntries(0)
                .mimeTypeFunction(new MimeTypeFunction() {
                    @Override
                    public MediaType guessFromPath(String path) {
                        return MediaType.JSON_UTF_8;
                    }

                    @Override
                    public MediaType guessFromPath(String path, @Nullable String contentEncoding) {
                        return MediaType.JSON_UTF_8;
                    }
                })
                .build());
        }
    };

    @Test
    void autoDecompress() {
        final FileService service = FileService.builder(FileServiceBuilderTest.class.getClassLoader(), "/")
                                               .serveCompressedFiles(true)
                                               .autoDecompress(true)
                                               .build();
        assertThat(service.config().autoDecompress()).isTrue();

        assertThatThrownBy(() -> FileService.builder(FileServiceBuilderTest.class.getClassLoader(), "/")
                                            .autoDecompress(true)
                                            .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should enable serveCompressedFiles when autoDecompress is set");
    }

    @Test
    void testCustomHardCodedMimeTypeFunction() {
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
          .get("/mimeTypeFunction/baz.txt").aggregate()
          .join();
        assertThat(response.headers().contentType().is(MediaType.JSON_UTF_8)).isTrue();
    }
}
