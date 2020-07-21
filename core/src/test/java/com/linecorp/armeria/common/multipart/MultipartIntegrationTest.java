/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;

class MultipartIntegrationTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/multipart", (ctx, req) -> {
                final Multipart multiPart = Multipart.from(req);
                final HttpResponseWriter writer = HttpResponse.streaming();
                final AtomicInteger count = new AtomicInteger();
                Flux.from(multiPart.bodyParts())
                    .subscribe(bodyPart -> {
                        if (count.getAndIncrement() == 0) {
                            assertThat(bodyPart.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
                            final ContentDisposition dispositionA = bodyPart.headers().contentDisposition();
                            assertThat(dispositionA.name()).isEqualTo("fieldA");
                            assertThat(dispositionA.type()).isEqualTo("form-data");
                            Flux.from(bodyPart.content())
                                .map(HttpData::toStringUtf8)
                                .collectList().subscribe(contents -> {
                                assertThat(contents).containsExactly("contentA");
                            });
                        } else {
                            assertThat(bodyPart.headers().contentType()).isEqualTo(MediaType.JSON);
                            final ContentDisposition dispositionB = bodyPart.headers().contentDisposition();
                            assertThat(dispositionB.name()).isEqualTo("fieldB");
                            assertThat(dispositionB.type()).isEqualTo("form-data");
                            Flux.from(bodyPart.content())
                                .map(HttpData::toStringUtf8)
                                .collectList().subscribe(contents -> {
                                assertThat(contents).containsExactly("{\"foo\":\"bar\"}");
                                writer.write(ResponseHeaders.of(200));
                                writer.close();
                            });
                        }
                    });
                return writer;
            });

            sb.service("/aggregate", (ctx, req) -> {
                final CompletableFuture<AggregatedMultipart> aggregated = Multipart.from(req).aggregate();
                final HttpResponseWriter writer = HttpResponse.streaming();
                aggregated.thenAccept(multipart -> {
                    int count = 0;
                    for (AggregatedBodyPart bodyPart : multipart.bodyParts()) {
                        if (count == 0) {
                            final ContentDisposition dispositionA = bodyPart.headers().contentDisposition();
                            assertThat(dispositionA.name()).isEqualTo("fieldA");
                            assertThat(dispositionA.type()).isEqualTo("form-data");

                            assertThat(bodyPart.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
                            assertThat(bodyPart.contentUtf8()).isEqualTo("contentA");
                        } else {
                            final ContentDisposition dispositionB = bodyPart.headers().contentDisposition();
                            assertThat(dispositionB.name()).isEqualTo("fieldB");
                            assertThat(dispositionB.type()).isEqualTo("form-data");

                            assertThat(bodyPart.headers().contentType()).isEqualTo(MediaType.JSON);
                            assertThat(bodyPart.content().toStringUtf8()).isEqualTo("{\"foo\":\"bar\"}");

                            writer.write(ResponseHeaders.of(200));
                            writer.close();
                        }
                        count++;
                    }
                });
                return writer;
            });

            sb.service("/simple", (ctx, req) -> {
                final Multipart multiPart = Multipart.from(req);
                Flux.from(multiPart.bodyParts())
                    .collectList()
                    .subscribe(bodyParts -> {
                        bodyParts.forEach(part -> {
                            Flux.from(part.content()).collectList()
                                .subscribe(contents -> {
                                    contents.forEach(d -> System.out.println("d = " + d.toStringUtf8()));
                                });
                        });
                    });
                return HttpResponse.of(200);
            });
        }
    };

    @CsvSource({"/multipart", "/aggregate"})
    @ParameterizedTest
    void multipart(String path) {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpHeaders headerA = HttpHeaders.builder()
                                               .contentDisposition(ContentDisposition.of("form-data", "fieldA"))
                                               .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                               .build();
        final BodyPart partA = BodyPart.builder()
                                       .headers(headerA)
                                       .content("contentA")
                                       .build();

        final ContentDisposition dispositionB = ContentDisposition.builder("form-data")
                                                                  .name("fieldB")
                                                                  .build();
        final HttpHeaders headerB = HttpHeaders.builder()
                                               .contentDisposition(dispositionB)
                                               .contentType(MediaType.JSON)
                                               .build();
        final BodyPart partB = BodyPart.builder()
                                       .headers(headerB)
                                       .content("{\"foo\":\"bar\"}")
                                       .build();

        final Multipart multiPart = Multipart.of(partA, partB);
        final AggregatedHttpResponse response =
                client.execute(multiPart.toHttpRequest(path)).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void simple() {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpHeaders headers = HttpHeaders.builder().build();
        final BodyPart bodyPart = BodyPart.builder()
                                          .headers(headers)
                                          .content("hello")
                                          .build();

        final Multipart multiPart = Multipart.of(bodyPart);

        final AggregatedHttpResponse response =
                client.execute(multiPart.toHttpRequest("/simple")).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }
}
