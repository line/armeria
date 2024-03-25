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
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ByteStreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;

class MultipartIntegrationTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/multipart", (ctx, req) -> {
                final Multipart multipart = Multipart.from(req);
                final HttpResponseWriter writer = HttpResponse.streaming();
                final AtomicInteger count = new AtomicInteger();
                Flux.from(multipart.bodyParts())
                    .subscribe(bodyPart -> {
                        if (count.getAndIncrement() == 0) {
                            assertThat(bodyPart.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
                            final ContentDisposition dispositionA = bodyPart.headers().contentDisposition();
                            assertThat(dispositionA.name()).isEqualTo("fieldA");
                            assertThat(dispositionA.type()).isEqualTo("form-data");
                            Flux.from(bodyPart.content())
                                .map(HttpData::toStringUtf8)
                                .collectList()
                                .subscribe(contents -> {
                                    assertThat(contents).containsExactly("contentA");
                                });
                        } else {
                            assertThat(bodyPart.headers().contentType()).isEqualTo(MediaType.JSON);
                            final ContentDisposition dispositionB = bodyPart.headers().contentDisposition();
                            assertThat(dispositionB.name()).isEqualTo("fieldB");
                            assertThat(dispositionB.type()).isEqualTo("form-data");
                            Flux.from(bodyPart.content())
                                .map(HttpData::toStringUtf8)
                                .collectList()
                                .subscribe(contents -> {
                                    assertThat(contents).containsExactly("{\"foo\":\"bar\"}");
                                    writer.write(ResponseHeaders.of(200));
                                    writer.close();
                                });
                        }
                    });
                return writer;
            });

            sb.service("/aggregate", (ctx, req) -> {
                final HttpResponseWriter writer = HttpResponse.streaming();
                final CompletableFuture<AggregatedMultipart> aggregated;
                final boolean pooled = "pooled".equals(ctx.query());
                if (pooled) {
                    aggregated = Multipart.from(req).aggregateWithPooledObjects(ctx.alloc());
                } else {
                    aggregated = Multipart.from(req).aggregate();
                }

                aggregated.thenAccept(multipart -> {
                    int count = 0;
                    for (AggregatedBodyPart bodyPart : multipart.bodyParts()) {
                        assertThat(bodyPart.content().isPooled()).isEqualTo(pooled);
                        if (count == 0) {
                            final ContentDisposition dispositionA = bodyPart.headers().contentDisposition();
                            assertThat(dispositionA.name()).isEqualTo("fieldA");
                            assertThat(multipart.field("fieldA")).isEqualTo(bodyPart);
                            assertThat(dispositionA.type()).isEqualTo("form-data");

                            assertThat(bodyPart.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
                            assertThat(bodyPart.contentUtf8()).isEqualTo("contentA");
                        } else {
                            final ContentDisposition dispositionB = bodyPart.headers().contentDisposition();
                            assertThat(dispositionB.name()).isEqualTo("fieldB");
                            assertThat(multipart.field("fieldB")).isEqualTo(bodyPart);
                            assertThat(dispositionB.type()).isEqualTo("form-data");

                            assertThat(bodyPart.headers().contentType()).isEqualTo(MediaType.JSON);
                            assertThat(bodyPart.content().toStringUtf8()).isEqualTo("{\"foo\":\"bar\"}");

                            writer.write(ResponseHeaders.of(200));
                            if (pooled) {
                                writer.write(HttpData.ofUtf8("pooled"));
                            }
                            writer.close();
                        }
                        bodyPart.content().close();
                        count++;
                    }
                });
                return writer;
            });

            sb.service("/simple", (ctx, req) -> {
                final Multipart multipart = Multipart.from(req);
                return HttpResponse.of(multipart.aggregate().thenApply(agg -> {
                    return HttpResponse.of(200);
                }));
            });

            sb.service("/pooled", (ctx, req) -> {
                final Multipart multipart = Multipart.from(req);
                final AtomicBoolean pooled = new AtomicBoolean();
                final HttpResponseWriter writer = HttpResponse.streaming();
                multipart.bodyParts()
                         .subscribe(new Subscriber<BodyPart>() {
                             @Override
                             public void onSubscribe(Subscription s) {
                                 s.request(Long.MAX_VALUE);
                             }

                             @Override
                             public void onNext(BodyPart bodyPart) {
                                 bodyPart.content().subscribe(new Subscriber<HttpData>() {
                                     @Override
                                     public void onSubscribe(Subscription s) {
                                         s.request(Long.MAX_VALUE);
                                     }

                                     @Override
                                     public void onNext(HttpData httpData) {
                                         pooled.set(httpData.isPooled());
                                         httpData.close();
                                     }

                                     @Override
                                     public void onError(Throwable t) {}

                                     @Override
                                     public void onComplete() {
                                         if (pooled.get()) {
                                             writer.write(ResponseHeaders.of(200));
                                         } else {
                                             writer.write(ResponseHeaders.of(500));
                                         }
                                         writer.close();
                                     }
                                 }, SubscriptionOption.WITH_POOLED_OBJECTS);
                             }

                             @Override
                             public void onError(Throwable t) {}

                             @Override
                             public void onComplete() {}
                         });
                return writer;
            });

            sb.service("/echo", (ctx, req) -> {
                return HttpResponse.of(
                        req.aggregate()
                           .thenApply(r -> HttpResponse.of(HttpStatus.OK,
                                                           requireNonNull(r.contentType(), "contentType"),
                                                           r.content()))
                           .exceptionally(HttpResponse::ofFailure));
            });

            sb.service("/multipart/response/simple", (ctx, req) -> {
                final HttpHeaders headers = HttpHeaders.builder().build();
                final BodyPart bodyPart = BodyPart.of(headers, "hello");
                final Multipart multipart = Multipart.of("multipart-response-simple", bodyPart);
                return multipart.toHttpResponse(HttpStatus.OK);
            });
        }
    };

    @CsvSource({ "/multipart", "/aggregate", "/aggregate?pooled" })
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

        final Multipart multipart = Multipart.of(partA, partB);
        final AggregatedHttpResponse response =
                client.execute(multipart.toHttpRequest(path)).aggregate().join();
        if ("/aggregate?pooled".equals(path)) {
            assertThat(response.contentUtf8()).isEqualTo("pooled");
        }
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    @CsvSource({ "/simple", "/pooled" })
    @ParameterizedTest
    void simple(String path) {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpHeaders headers = HttpHeaders.builder().build();
        final BodyPart bodyPart = BodyPart.of(headers, "hello");

        final Multipart multipart = Multipart.of(bodyPart);

        final AggregatedHttpResponse response =
                client.execute(multipart.toHttpRequest(path)).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void pingPong() {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpHeaders headers = HttpHeaders.builder().build();
        final BodyPart bodyPart = BodyPart.of(headers, "hello");

        final Multipart requestMultipart = Multipart.of(bodyPart);

        final HttpResponse response = client.execute(requestMultipart.toHttpRequest("/echo"));

        final SplitHttpResponse splitResponse = response.split();
        final ResponseHeaders responseHeaders = splitResponse.headers().join();
        assertThat(responseHeaders.status()).isEqualTo(HttpStatus.OK);
        final ByteStreamMessage responseContents = splitResponse.body();
        @Nullable
        final MediaType contentType = responseHeaders.contentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isMultipart()).isTrue();
        final String boundary = Multiparts.getBoundary(contentType);
        assertThat(boundary).isEqualTo(requestMultipart.boundary());

        final Multipart responseMultipart = Multipart.from(boundary, responseContents);
        assertThat(responseMultipart.boundary()).isEqualTo(boundary);
        final AggregatedMultipart aggregatedMultipart = responseMultipart.aggregate().join();
        assertThat(aggregatedMultipart.bodyParts()).hasSize(1);
        assertThat(aggregatedMultipart.bodyParts().get(0).contentUtf8()).isEqualTo("hello");
    }

    @Test
    void multipartSimpleResponse() {
        final WebClient client = WebClient.of(server.httpUri());

        final HttpResponse response = client.get("/multipart/response/simple");

        final SplitHttpResponse splitResponse = response.split();
        final ResponseHeaders responseHeaders = splitResponse.headers().join();
        assertThat(responseHeaders.status()).isEqualTo(HttpStatus.OK);
        final ByteStreamMessage responseContents = splitResponse.body();
        @Nullable
        final MediaType contentType = responseHeaders.contentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isMultipart()).isTrue();
        final String boundary = Multiparts.getBoundary(contentType);
        assertThat(boundary).isEqualTo("multipart-response-simple");

        final Multipart responseMultipart = Multipart.from(boundary, responseContents);
        assertThat(responseMultipart.boundary()).isEqualTo(boundary);
        final AggregatedMultipart aggregatedMultipart = responseMultipart.aggregate().join();
        assertThat(aggregatedMultipart.bodyParts()).hasSize(1);
        assertThat(aggregatedMultipart.bodyParts().get(0).contentUtf8()).isEqualTo("hello");
    }

    @Test
    void requestContentType() {
        // this tests the situation when Content-Type header already present at RequestHeaders
        final HttpHeaders bodyPartHeaders = HttpHeaders.builder().build();
        final BodyPart bodyPart = BodyPart.of(bodyPartHeaders, "hello");

        final Multipart multipart = Multipart.of(bodyPart);
        final RequestHeaders requestHeaders = RequestHeaders.builder(HttpMethod.POST, "/simple")
                                                            .contentType(MediaType.MULTIPART_FORM_DATA)
                                                            .build();
        final HttpRequest request = multipart.toHttpRequest(requestHeaders);
        assertThat(request.headers().getAll(HttpHeaderNames.CONTENT_TYPE)).hasSize(1);
    }

    @Test
    void responseContentType() {
        // this tests the situation when Content-Type header already present at ResponseHeaders
        final HttpHeaders bodyPartHeaders = HttpHeaders.builder().build();
        final BodyPart bodyPart = BodyPart.of(bodyPartHeaders, "hello");

        final Multipart multipart = Multipart.of(bodyPart);
        final ResponseHeaders responseHeaders = ResponseHeaders.builder(HttpStatus.OK)
                                                               .contentType(MediaType.MULTIPART_FORM_DATA)
                                                               .build();
        final HttpResponse response = multipart.toHttpResponse(responseHeaders);
        assertThat(response.aggregate().join().headers().getAll(HttpHeaderNames.CONTENT_TYPE)).hasSize(1);
    }
}
