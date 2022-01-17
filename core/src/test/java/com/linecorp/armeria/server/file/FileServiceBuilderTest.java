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

    private static final String BASE_RESOURCE_DIR =
            FileServiceBuilderTest.class.getPackage().getName().replace('.', '/') + '/';

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.serviceUnder(
                    "/mediaTypeResolver",
                    FileService.builder(getClass().getClassLoader(), BASE_RESOURCE_DIR + "bar")
                               .mediaTypeResolver(new MediaTypeResolver() {
                                   @Override
                                   public MediaType guessFromPath(String path) {
                                       if (path.endsWith(".custom-json-extension")) {
                                           return MediaType.JSON_UTF_8;
                                       }
                                       if (path.endsWith(".custom-txt-extension")) {
                                           return MediaType.PLAIN_TEXT_UTF_8;
                                       }
                                       return null;
                                   }

                                   @Override
                                   public MediaType guessFromPath(String path,
                                                                  @Nullable String contentEncoding) {
                                       if (contentEncoding == null) {
                                           return guessFromPath(path);
                                       }
                                       return null;
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
    void testCustomMediaTypeResolverGuessFromPathCustomJsonExtension() {
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .get("/mediaTypeResolver/bar.custom-json-extension")
                                                         .aggregate()
                                                         .join();
        assertThat(response.headers().contentType()).isSameAs(MediaType.JSON_UTF_8);
    }

    @Test
    void testCustomMediaTypeResolverGuessFromPathCustomTextExtension() {
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .get("/mediaTypeResolver/bar.custom-txt-extension")
                                                         .aggregate()
                                                         .join();
        assertThat(response.headers().contentType()).isSameAs(MediaType.PLAIN_TEXT_UTF_8);
    }

    @Test
    void testCustomMediaTypeResolverNotMatchThenDefaultIsUsed() {
        final AggregatedHttpResponse response = WebClient.of(server.httpUri())
                                                         .get("/mediaTypeResolver/bar.xhtml").aggregate()
                                                         .join();
        assertThat(response.headers().contentType()).isSameAs(MediaType.XHTML_UTF_8);
    }
}
