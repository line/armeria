/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.server.annotation;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.AdditionalHeader;
import com.linecorp.armeria.server.annotation.AdditionalTrailer;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.HttpResult;
import com.linecorp.armeria.server.annotation.NullToNoContentResponseConverterFunction;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesEventStream;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ProducesJsonSequences;
import com.linecorp.armeria.server.annotation.ProducesOctetStream;
import com.linecorp.armeria.server.annotation.ProducesText;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AnnotatedServiceResponseConverterTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String STRING = "₩";
    private static final byte[] BYTEARRAY = STRING.getBytes(StandardCharsets.UTF_8);
    private static final HttpData HTTPDATA = HttpData.wrap(BYTEARRAY);
    private static final JsonNode JSONNODE;
    private static final HttpFile HTTPFILE = HttpFile.of(HTTPDATA);

    static {
        try {
            JSONNODE = mapper.readTree("{\"a\":\"₩\"}");
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/type", new Object() {
                @Get("/string")
                public String string() {
                    return STRING;
                }

                @Get("/byte-array")
                public byte[] byteArray() {
                    return BYTEARRAY;
                }

                @Get("/http-data")
                public HttpData httpData() {
                    return HTTPDATA;
                }

                @Get("/json-node")
                public JsonNode jsonNode() throws IOException {
                    return JSONNODE;
                }

                @Get("/http-file")
                public HttpFile httpFile() {
                    return HTTPFILE;
                }
            });

            sb.annotatedService("/publish/single", new Object() {
                @Get("/string")
                @ProducesText   // Can omit this annotation, but it's not recommended.
                public Publisher<String> string() {
                    return Mono.just(STRING);
                }

                @Get("/byte-array")
                @ProducesOctetStream
                public Publisher<byte[]> byteArray() {
                    return new ObjectPublisher<>(BYTEARRAY);
                }

                @Get("/http-data")
                @ProducesOctetStream
                public Publisher<HttpData> httpData() {
                    return new ObjectPublisher<>(HTTPDATA);
                }

                @Get("/json-node")
                @ProducesJson   // Can omit this annotation, but it's not recommended.
                public Publisher<JsonNode> jsonNode() throws IOException {
                    return Mono.just(JSONNODE);
                }

                @Get("/http-file")
                public Publisher<HttpFile> httpFile() {
                    return Mono.just(HTTPFILE);
                }
            });

            sb.annotatedService("/publish/multi", new Object() {
                @Get("/string")
                @ProducesJson
                public Publisher<String> string() {
                    return new ObjectPublisher<>("a", "b", "c");
                }

                @Get("/json-node")
                @ProducesJson
                public Publisher<JsonNode> jsonNode() throws IOException {
                    return new ObjectPublisher<>(mapper.readTree("{\"a\":\"1\"}"),
                                                 mapper.readTree("{\"b\":\"2\"}"),
                                                 mapper.readTree("{\"c\":\"3\"}"));
                }
            });

            sb.annotatedService("/publish/failure", new Object() {
                @Get("/immediate")
                public Publisher<Object> immediate() {
                    throw new IllegalArgumentException("Bad request!");
                }

                @Get("/defer")
                @ProducesText
                public Publisher<String> defer() {
                    return exceptionRaisingPublisher();
                }
            });

            sb.annotatedService("/publish/http-result", new Object() {
                @Get("/mono/json-node")
                public HttpResult<Publisher<JsonNode>> monoJsonNode() throws IOException {
                    return HttpResult.of(Mono.just(JSONNODE));
                }

                @Get("/json-node")
                @ProducesJson
                public HttpResult<Publisher<JsonNode>> jsonNode() throws IOException {
                    return HttpResult.of(new ObjectPublisher<>(JSONNODE));
                }

                @Get("/defer")
                public HttpResult<Publisher<String>> defer() {
                    return HttpResult.of(exceptionRaisingPublisher());
                }
            });

            sb.annotatedService("/publish/response-entity", new Object() {
                @Get("/mono/json-node")
                public ResponseEntity<Publisher<JsonNode>> monoJsonNode() throws IOException {
                    return ResponseEntity.of(Mono.just(JSONNODE));
                }

                @Get("/json-node")
                @ProducesJson
                public ResponseEntity<Publisher<JsonNode>> jsonNode() throws IOException {
                    return ResponseEntity.of(new ObjectPublisher<>(JSONNODE));
                }

                @Get("/defer")
                public ResponseEntity<Publisher<String>> defer() {
                    return ResponseEntity.of(exceptionRaisingPublisher());
                }
            });

            sb.annotatedService("/produce", new Object() {
                @Get("/string")
                @ProducesText
                public int string() {
                    return 100;
                }

                @Get("/byte-array")
                @UserProduceBinary
                public byte[] byteArray() {
                    return BYTEARRAY;
                }

                @Get("/http-data")
                @Produces("application/octet-stream")
                public HttpData httpData() {
                    return HTTPDATA;
                }

                @Get("/byte-array-gif")
                @Produces("image/gif")
                public byte[] byteArrayGif() {
                    return BYTEARRAY;
                }

                @Get("/http-data-png")
                @Produces("image/png")
                public HttpData httpDataPng() {
                    return HTTPDATA;
                }

                @Get("/byte-array-txt")
                @ProducesText
                public byte[] byteArrayTxt() {
                    return BYTEARRAY;
                }

                @Get("/http-data-txt")
                @ProducesText
                public HttpData httpDataTxt() {
                    return HTTPDATA;
                }

                @Get("/json-node")
                @ProducesJson
                public Map<String, String> jsonNode() throws IOException {
                    return ImmutableMap.of("a", STRING);
                }
            });

            sb.annotatedService("/custom-response", new Object() {
                @Get("/expect-specified-status")
                @StatusCode(202)
                public Void expectSpecifiedStatus() {
                    // Will send '202 Accepted' because a user specified it with @StatusCode annotation.
                    return null;
                }

                @Get("/expect-no-content")
                public void expectNoContent() {
                    // Will send '204 No Content' because the return type is 'void'.
                    return;
                }

                @Get("/expect-ok")
                public Object expectOk() {
                    // Will send '200 OK' because there is no @StatusCode annotation, that means
                    // '200 OK' will be used by default because the return type is not a 'void' or 'Void'.
                    return null;
                }

                @Get("/header")
                @AdditionalHeader(name = "header_name_1", value = "header_value_1")
                @AdditionalHeader(name = "header_name_2", value = "header_value_2")
                @AdditionalHeader(name = "header_name_1", value = "header_value_3")
                public void header() {}

                @Get("/header-overwrite")
                @AdditionalHeader(name = "header_name_1", value = "header_value_changed")
                public HttpResponse headerOverwrite() {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK,
                                                              HttpHeaderNames.of("header_name_1"),
                                                              "header_value_unchanged"));
                }
            });

            sb.annotatedService("/custom-response/http-result", new Object() {
                @Get("/expect-specified-no-content")
                @StatusCode(204)
                public HttpResult<Object> expectSpecifiedNoContent() {
                    // Will send '204 No Content' because it is specified with @StatusCode annotation.
                    return null;
                }

                @Get("/expect-not-modified")
                @StatusCode(204)
                public HttpResult<Object> expectNotModified() {
                    // Will send '304 Not Modified' because HttpResult overrides the @StatusCode
                    // annotation.
                    return HttpResult.of(HttpStatus.NOT_MODIFIED);
                }

                @Get("/expect-unauthorized")
                public HttpResult<HttpResponse> expectUnauthorized() {
                    // Will send '401 Unauthorized' because the content of HttpResult is HttpResponse.
                    return HttpResult.of(HttpStatus.OK, HttpResponse.of(HttpStatus.UNAUTHORIZED));
                }

                @Get("/expect-no-content-from-converter")
                @ResponseConverter(NullToNoContentResponseConverterFunction.class)
                public HttpResult<Object> expectNoContentFromConverter() {
                    // Will send '204 No Content' which is converted by
                    // NullToNoContentResponseConverterFunction.
                    return null;
                }

                @Get("/expect-custom-header")
                @AdditionalHeader(name = "x-custom-annotated-header", value = "annotated-value")
                @ProducesJson
                public HttpResult<Map<String, String>> expectCustomHeader() {
                    return HttpResult.of(HttpHeaders.of(HttpHeaderNames.of("x-custom-header"), "value"),
                                         ImmutableMap.of("a", "b"));
                }

                @Get("/expect-custom-trailers")
                @AdditionalTrailer(name = "x-custom-annotated-trailers", value = "annotated-value")
                @ProducesJson
                public HttpResult<List<String>> expectCustomTrailers() {
                    return HttpResult.of(HttpHeaders.of(HttpHeaderNames.of("x-custom-header"), "value"),
                                         ImmutableList.of("a", "b"),
                                         HttpHeaders
                                                 .of(HttpHeaderNames.of("x-custom-trailers"), "value"));
                }

                @Get("/async/expect-custom-header")
                @AdditionalHeader(name = "x-custom-annotated-header", value = "annotated-value")
                @ProducesJson
                public HttpResult<CompletionStage<Map<String, String>>> asyncExpectCustomHeader() {
                    return HttpResult.of(HttpHeaders.of(HttpHeaderNames.of("x-custom-header"), "value"),
                                         UnmodifiableFuture.completedFuture(ImmutableMap.of("a", "b")));
                }

                @Get("/async/expect-custom-trailers")
                @AdditionalTrailer(name = "x-custom-annotated-trailers", value = "annotated-value")
                @ProducesJson
                public HttpResult<CompletionStage<List<String>>> asyncExpectCustomTrailers(
                        ServiceRequestContext ctx) {
                    final CompletableFuture<List<String>> future = new CompletableFuture<>();
                    ctx.eventLoop().schedule(() -> future.complete(ImmutableList.of("a", "b")),
                                             1, TimeUnit.SECONDS);
                    return HttpResult.of(HttpHeaders.of(HttpHeaderNames.of("x-custom-header"), "value"),
                                         future,
                                         HttpHeaders
                                                 .of(HttpHeaderNames.of("x-custom-trailers"), "value"));
                }

                @Get("/async/expect-bad-request")
                public HttpResult<CompletionStage<Object>> asyncExpectBadRequest() {
                    final CompletableFuture<Object> future = new CompletableFuture<>();
                    future.completeExceptionally(new IllegalArgumentException("Bad arguments"));
                    return HttpResult.of(ResponseHeaders.of(HttpStatus.OK), future);
                }

                @Get("/wildcard")
                @ProducesJson
                public HttpResult<?> wildcard() {
                    return HttpResult.of(ImmutableList.of("a", "b"));
                }

                @Get("/generic")
                @ProducesJson
                @SuppressWarnings("unchecked")
                public <T> HttpResult<T> generic() {
                    return (HttpResult<T>) HttpResult.of(ImmutableList.of("a", "b"));
                }
            });

            sb.annotatedService("/custom-response/response-entity", new Object() {
                @Get("/expect-specified-no-content")
                @StatusCode(204)
                public ResponseEntity<Void> expectSpecifiedNoContent() {
                    // Will send '204 No Content' because it is specified with @StatusCode annotation.
                    return null;
                }

                @Get("/expect-not-modified")
                @StatusCode(204)
                public ResponseEntity<Void> expectNotModified() {
                    // Will send '304 Not Modified' because ResponseEntity overrides the @StatusCode
                    // annotation.
                    return ResponseEntity.of(ResponseHeaders.of(HttpStatus.NOT_MODIFIED));
                }

                @Get("/expect-unauthorized")
                public ResponseEntity<HttpResponse> expectUnauthorized() {
                    // Will send '401 Unauthorized' because the content of ResponseEntity is HttpResponse.
                    return ResponseEntity.of(
                            ResponseHeaders.of(HttpStatus.OK),
                            HttpResponse.of(HttpStatus.UNAUTHORIZED));
                }

                @Get("/expect-no-content-from-converter")
                @ResponseConverter(NullToNoContentResponseConverterFunction.class)
                public ResponseEntity<Object> expectNoContentFromConverter() {
                    // Will send '204 No Content' which is converted by
                    // NullToNoContentResponseConverterFunction.
                    return null;
                }

                @Get("/expect-custom-header")
                @AdditionalHeader(name = "x-custom-annotated-header", value = "annotated-value")
                @ProducesJson
                public ResponseEntity<Map<String, String>> expectCustomHeader() {
                    final ResponseHeaders responseHeaders = ResponseHeaders.of(
                            HttpStatus.OK,
                            HttpHeaderNames.of("x-custom-header"), "value");
                    final Map<String, String> content = ImmutableMap.of("a", "b");
                    return ResponseEntity.of(responseHeaders, content);
                }

                @Get("/async/expect-custom-header")
                @AdditionalHeader(name = "x-custom-annotated-header", value = "annotated-value")
                @ProducesJson
                public ResponseEntity<CompletionStage<Map<String, String>>> asyncExpectCustomHeader() {
                    final ResponseHeaders responseHeaders = ResponseHeaders.of(
                            HttpStatus.OK,
                            HttpHeaderNames.of("x-custom-header"), "value");
                    final CompletionStage<Map<String, String>> content =
                            UnmodifiableFuture.completedFuture(ImmutableMap.of("a", "b"));
                    return ResponseEntity.of(responseHeaders, content);
                }

                @Get("/expect-custom-trailers")
                @AdditionalTrailer(name = "x-custom-annotated-trailers", value = "annotated-value")
                @ProducesJson
                public ResponseEntity<List<String>> expectCustomTrailers() {
                    final ResponseHeaders responseHeaders = ResponseHeaders.of(
                            HttpStatus.OK,
                            HttpHeaderNames.of("x-custom-header"), "value");
                    final List<String> content = ImmutableList.of("a", "b");
                    final HttpHeaders trailers =
                            HttpHeaders.of(HttpHeaderNames.of("x-custom-trailers"), "value");
                    return ResponseEntity.of(responseHeaders, content, trailers);
                }

                @Get("/async/expect-custom-trailers")
                @AdditionalTrailer(name = "x-custom-annotated-trailers", value = "annotated-value")
                @ProducesJson
                public ResponseEntity<CompletionStage<List<String>>> asyncExpectCustomTrailers(
                        ServiceRequestContext ctx) {
                    final ResponseHeaders responseHeaders = ResponseHeaders.of(
                            HttpStatus.OK,
                            HttpHeaderNames.of("x-custom-header"), "value");
                    final CompletableFuture<List<String>> future = new CompletableFuture<>();
                    ctx.eventLoop().schedule(() -> future.complete(ImmutableList.of("a", "b")),
                                             1, TimeUnit.SECONDS);
                    final HttpHeaders trailers =
                            HttpHeaders.of(HttpHeaderNames.of("x-custom-trailers"), "value");
                    return ResponseEntity.of(responseHeaders, future, trailers);
                }

                @Get("/async/expect-bad-request")
                public ResponseEntity<CompletionStage<Object>> asyncExpectBadRequest() {
                    final CompletableFuture<Object> future = new CompletableFuture<>();
                    future.completeExceptionally(new IllegalArgumentException("Bad arguments"));
                    return ResponseEntity.of(ResponseHeaders.of(HttpStatus.OK), future);
                }

                @Get("/wildcard")
                @ProducesJson
                public ResponseEntity<?> wildcard() {
                    return ResponseEntity.of(ImmutableList.of("a", "b"));
                }

                @Get("/generic")
                @ProducesJson
                @SuppressWarnings("unchecked")
                public <T> ResponseEntity<T> generic() {
                    return (ResponseEntity<T>) ResponseEntity.of(ImmutableList.of("a", "b"));
                }
            });

            sb.annotatedService("/http-file", new Object() {
                @Get("/expect-custom-header")
                @AdditionalHeader(name = "x-custom-annotated-header", value = "annotated-value")
                public ResponseEntity<HttpFile> httpFileExpectCustomHeader() {
                    final ResponseHeaders responseHeaders = ResponseHeaders.of(
                            HttpStatus.OK,
                            HttpHeaderNames.of("x-custom-header"), "value");
                    return ResponseEntity.of(responseHeaders, HTTPFILE);
                }

                @Get("/expect-custom-trailers")
                @AdditionalTrailer(name = "x-custom-annotated-trailers", value = "annotated-value")
                public ResponseEntity<HttpFile> httpFileExpectCustomTrailers() {
                    final ResponseHeaders responseHeaders = ResponseHeaders.of(
                            HttpStatus.OK,
                            HttpHeaderNames.of("x-custom-header"), "value");
                    final HttpHeaders trailers =
                            HttpHeaders.of(HttpHeaderNames.of("x-custom-trailers"), "value");
                    return ResponseEntity.of(responseHeaders, HTTPFILE, trailers);
                }

                @Get("/expect-http-file-service-headers-not-overwritten")
                @StatusCode(400)
                @AdditionalHeader(name = "x-custom-annotated-header", value = "annotated-value")
                public ResponseEntity<HttpFile> httpFileExpectHeadersNotOverwritten() {
                    final ResponseHeaders responseHeaders = ResponseHeaders.of(
                            HttpStatus.UNAUTHORIZED,
                            HttpHeaderNames.of("x-custom-header"), "value");
                    final HttpHeaders trailers =
                            HttpHeaders.of(HttpHeaderNames.of("x-custom-trailers"), "value");
                    return ResponseEntity.of(responseHeaders, HTTPFILE, trailers);
                }
            });

            sb.annotatedService("/custom-classlevel", new AnnotatedService());

            sb.annotatedService("/json-seq", new Object() {
                @Get("/stream")
                @ProducesJsonSequences
                public Stream<String> stream() {
                    return Stream.of("foo", "bar", "baz", "qux");
                }

                @Get("/publisher")
                @ProducesJsonSequences
                public Publisher<String> publisher() {
                    return Flux.just("foo", "bar", "baz", "qux");
                }
            });

            sb.annotatedService("/event-stream", new Object() {
                @Get("/stream")
                @ProducesEventStream
                public Stream<ServerSentEvent> stream() {
                    return Stream.of(ServerSentEvent.ofData("foo"),
                                     ServerSentEvent.ofData("bar"),
                                     ServerSentEvent.ofData("baz"),
                                     ServerSentEvent.ofData("qux"));
                }

                @Get("/publisher")
                @ProducesEventStream
                public Publisher<ServerSentEvent> publisher() {
                    return Flux.just(ServerSentEvent.ofData("foo"),
                                     ServerSentEvent.ofData("bar"),
                                     ServerSentEvent.ofData("baz"),
                                     ServerSentEvent.ofData("qux"));
                }
            });

            sb.disableServerHeader();
            sb.disableDateHeader();
        }

        private Publisher<String> exceptionRaisingPublisher() {
            return s -> s.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    s.onNext("a");
                    s.onError(new IllegalArgumentException("Bad request!"));
                }

                @Override
                public void cancel() {}
            });
        }
    };

    @AdditionalHeader(name = "class_header_1", value = "class_value_1")
    @AdditionalHeader(name = "class_header_2", value = "class_value_2")
    @AdditionalHeader(name = "overwritten_1", value = { "unchanged_1", "unchanged_2" })
    @AdditionalTrailer(name = "class_trailer_1", value = "class_value_1")
    @AdditionalTrailer(name = "class_trailer_2", value = "class_value_2")
    private static class AnnotatedService {

        @Get("/expect-class")
        public void expectClass() {}

        @Get("/expect-combined")
        @AdditionalHeader(name = "method_header_1", value = "method_value_1")
        @AdditionalTrailer(name = "method_trailer_1", value = "method_value_1")
        public String expectCombined() {
            return "combined";
        }

        @Get("/expect-combined2")
        @AdditionalHeader(name = "method_header_1", value = "method_value_1")
        @AdditionalHeader(name = "method_header_2", value = "method_value_2")
        @AdditionalTrailer(name = "method_trailer_1", value = "method_value_1")
        @AdditionalTrailer(name = "method_trailer_2", value = "method_value_2")
        public String expectCombined2() {
            return "combined2";
        }

        @Get("/expect-overwritten")
        @AdditionalHeader(name = "overwritten_1", value = { "overwritten_value_1", "overwritten_value_2" })
        public HttpResponse expectOverwritten() {
            return HttpResponse.of("overwritten");
        }
    }

    private static class ObjectPublisher<T> implements Publisher<T> {
        private final List<T> objects;

        @SafeVarargs
        ObjectPublisher(T... objects) {
            this.objects = ImmutableList.copyOf(objects);
        }

        @Override
        public void subscribe(Subscriber<? super T> s) {
            s.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    final int size = objects.size();
                    assertThat(n).isGreaterThanOrEqualTo(size);
                    for (int i = 0; i < size; i++) {
                        s.onNext(objects.get(i));
                    }
                    s.onComplete();
                }

                @Override
                public void cancel() {
                    s.onError(CancelledSubscriptionException.get());
                }
            });
        }
    }

    @Test
    void customizedClassLevelResponse() {
        final WebClient client = WebClient.of(server.httpUri() + "/custom-classlevel");
        AggregatedHttpResponse res;

        res = aggregated(client.get("/expect-class"));

        assertThat(res.headers().get(HttpHeaderNames.of("class_header_1"))).isEqualTo("class_value_1");
        assertThat(res.headers().get(HttpHeaderNames.of("class_header_2"))).isEqualTo("class_value_2");

        assertThat(res.headers().getAll(HttpHeaderNames.of("overwritten_1"))).containsExactly(
                "unchanged_1", "unchanged_2");

        res = aggregated(client.get("/expect-combined"));

        assertThat(res.headers().get(HttpHeaderNames.of("class_header_1"))).isEqualTo("class_value_1");
        assertThat(res.headers().get(HttpHeaderNames.of("class_header_2"))).isEqualTo("class_value_2");
        assertThat(res.trailers().get(HttpHeaderNames.of("class_trailer_1"))).isEqualTo("class_value_1");
        assertThat(res.trailers().get(HttpHeaderNames.of("class_trailer_2"))).isEqualTo("class_value_2");
        assertThat(res.headers().get(HttpHeaderNames.of("method_header_1"))).isEqualTo("method_value_1");
        assertThat(res.trailers().get(HttpHeaderNames.of("method_trailer_1"))).isEqualTo(
                "method_value_1");

        res = aggregated(client.get("/expect-combined2"));
        assertThat(res.headers().get(HttpHeaderNames.of("class_header_1"))).isEqualTo("class_value_1");
        assertThat(res.headers().get(HttpHeaderNames.of("class_header_2"))).isEqualTo("class_value_2");
        assertThat(res.headers().get(HttpHeaderNames.of("method_header_1"))).isEqualTo("method_value_1");
        assertThat(res.headers().get(HttpHeaderNames.of("method_header_2"))).isEqualTo("method_value_2");
        assertThat(res.trailers().get(HttpHeaderNames.of("method_trailer_1"))).isEqualTo(
                "method_value_1");
        assertThat(res.trailers().get(HttpHeaderNames.of("method_trailer_2"))).isEqualTo(
                "method_value_2");

        res = aggregated(client.get("/expect-overwritten"));
        assertThat(res.headers().get(HttpHeaderNames.of("class_header_1"))).isEqualTo("class_value_1");
        assertThat(res.headers().get(HttpHeaderNames.of("class_header_2"))).isEqualTo("class_value_2");
        assertThat(res.headers().getAll(HttpHeaderNames.of("overwritten_1"))).containsExactly(
                "overwritten_value_1", "overwritten_value_2");
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @Produces("application/octet-stream")
    @interface UserProduceBinary {}

    @Test
    void typeBasedDefaultResponseConverter() throws Exception {
        shouldBeConvertedByDefaultResponseConverter(WebClient.of(server.httpUri() + "/type"));
    }

    @Test
    void publisherBasedResponseConverter() throws Exception {
        shouldBeConvertedByDefaultResponseConverter(WebClient.of(server.httpUri() + "/publish/single"));
    }

    private static void shouldBeConvertedByDefaultResponseConverter(WebClient client) throws Exception {
        AggregatedHttpResponse res;

        res = aggregated(client.get("/string"));
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.contentUtf8()).isEqualTo(STRING);
        assertThat(res.contentAscii()).isNotEqualTo(STRING);

        res = aggregated(client.get("/byte-array"));
        assertThat(res.contentType()).isEqualTo(MediaType.OCTET_STREAM);
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);

        res = aggregated(client.get("/http-data"));
        assertThat(res.contentType()).isEqualTo(MediaType.OCTET_STREAM);
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);

        res = aggregated(client.get("/json-node"));
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(res.content().array()).isEqualTo(mapper.writeValueAsBytes(JSONNODE));

        res = aggregated(client.get("/http-file"));
        assertThat(res.contentType()).isNull();
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);
    }

    @Test
    void multipleObjectPublisherBasedResponseConverter() throws Exception {
        final WebClient client = WebClient.of(server.httpUri() + "/publish/multi");

        AggregatedHttpResponse res;

        res = aggregated(client.get("/string"));
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8())
                .isArray().ofLength(3)
                .thatContains("a").thatContains("b").thatContains("c");

        res = aggregated(client.get("/json-node"));
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8())
                .isEqualTo("[{\"a\":\"1\"},{\"b\":\"2\"},{\"c\":\"3\"}]");
    }

    @Test
    void publisherBasedResponseConversionFailure() throws Exception {
        final WebClient client = WebClient.of(server.httpUri() + "/publish/failure");

        AggregatedHttpResponse res;

        res = aggregated(client.get("/immediate"));
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        res = aggregated(client.get("/defer"));
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void produceTypeAnnotationBasedDefaultResponseConverter() throws Exception {
        final WebClient client = WebClient.of(server.httpUri() + "/produce");

        AggregatedHttpResponse res;

        res = aggregated(client.get("/string"));
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.content().array()).isEqualTo("100".getBytes());

        res = aggregated(client.get("/byte-array"));
        assertThat(res.contentType()).isEqualTo(MediaType.OCTET_STREAM);
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);

        res = aggregated(client.get("/http-data"));
        assertThat(res.contentType()).isEqualTo(MediaType.OCTET_STREAM);
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);

        res = aggregated(client.get("/byte-array-gif"));
        assertThat(res.contentType()).isEqualTo(MediaType.GIF);
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);

        res = aggregated(client.get("/http-data-png"));
        assertThat(res.contentType()).isEqualTo(MediaType.PNG);
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);

        res = aggregated(client.get("/byte-array-txt"));
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);

        res = aggregated(client.get("/http-data-txt"));
        assertThat(res.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);

        res = aggregated(client.get("/json-node"));
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(res.content().array()).isEqualTo(mapper.writeValueAsBytes(JSONNODE));
    }

    @Test
    void customizedHttpResponse() {
        final WebClient client = WebClient.of(server.httpUri() + "/custom-response");

        AggregatedHttpResponse res;

        res = aggregated(client.get("/expect-specified-status"));
        assertThat(res.status()).isEqualTo(HttpStatus.ACCEPTED);

        res = aggregated(client.get("/expect-no-content"));
        assertThat(res.status()).isEqualTo(HttpStatus.NO_CONTENT);

        res = aggregated(client.get("/expect-ok"));
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        res = aggregated(client.get("/header"));
        assertThat(res.status()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(res.headers().getAll(HttpHeaderNames.of("header_name_1")).toString()).isEqualTo(
                "[header_value_1]");

        res = aggregated(client.get("/header-overwrite"));
        assertThat(res.headers().get(HttpHeaderNames.of("header_name_1"))).isEqualTo("header_value_changed");
    }

    @ParameterizedTest
    @CsvSource({ "/http-result", "/response-entity" })
    void customizedHttpResponseWithMultiInterfaces(String pathPrefix) {
        final WebClient client = WebClient.of(server.httpUri() + "/custom-response" + pathPrefix);

        AggregatedHttpResponse res;

        res = aggregated(client.get("/expect-specified-no-content"));
        assertThat(res.status()).isEqualTo(HttpStatus.NO_CONTENT);

        res = aggregated(client.get("/expect-not-modified"));
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);

        res = aggregated(client.get("/expect-unauthorized"));
        assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        res = aggregated(client.get("/expect-no-content-from-converter"));
        assertThat(res.status()).isEqualTo(HttpStatus.NO_CONTENT);

        ImmutableList.of("/expect-custom-header",
                         "/async/expect-custom-header").forEach(path -> {
            final AggregatedHttpResponse response = aggregated(client.get(path));
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.headers().get(HttpHeaderNames.of("x-custom-header"))).isEqualTo("value");
            assertThatJson(response.contentUtf8()).isEqualTo(ImmutableMap.of("a", "b"));
            assertThat(response.headers().get(HttpHeaderNames.of("x-custom-annotated-header"))).isEqualTo(
                    "annotated-value");
        });

        ImmutableList.of("/expect-custom-trailers",
                         "/async/expect-custom-trailers").forEach(path -> {
            final AggregatedHttpResponse response = aggregated(client.get(path));
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.headers().get(HttpHeaderNames.of("x-custom-header"))).isEqualTo("value");
            assertThatJson(response.contentUtf8()).isEqualTo(ImmutableList.of("a", "b"));
            assertThat(response.trailers().get(HttpHeaderNames.of("x-custom-trailers")))
                    .isEqualTo("value");
            assertThat(response.trailers().get(HttpHeaderNames.of("x-custom-annotated-trailers")))
                    .isEqualTo("annotated-value");
        });

        res = aggregated(client.get("/async/expect-bad-request"));
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        ImmutableList.of("/wildcard", "/generic").forEach(path -> {
            final AggregatedHttpResponse response = aggregated(client.get(path));
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThatJson(response.contentUtf8()).isEqualTo(ImmutableList.of("a", "b"));
        });
    }

    @Test
    void httpResultWithPublisher() {
        final WebClient client = WebClient.of(server.httpUri() + "/publish/http-result");

        AggregatedHttpResponse res;

        res = aggregated(client.get("/mono/json-node"));
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).isEqualTo(ImmutableMap.of("a", STRING));

        res = aggregated(client.get("/json-node"));
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).isEqualTo(ImmutableList.of(ImmutableMap.of("a", STRING)));

        res = aggregated(client.get("/defer"));
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void responseEntityWithPublisher() {
        final WebClient client = WebClient.of(server.httpUri() + "/publish/response-entity");

        AggregatedHttpResponse res;

        res = aggregated(client.get("/mono/json-node"));
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).isEqualTo(ImmutableMap.of("a", STRING));

        res = aggregated(client.get("/json-node"));
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).isEqualTo(ImmutableList.of(ImmutableMap.of("a", STRING)));

        res = aggregated(client.get("/defer"));
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void httpFileResponseConverter() {
        final WebClient client = WebClient.of(server.httpUri() + "/http-file");

        AggregatedHttpResponse res;

        res = aggregated(client.get("/expect-custom-header"));
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.of("x-custom-header"))).isEqualTo("value");
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);
        assertThat(res.headers().get(HttpHeaderNames.of("x-custom-annotated-header"))).isEqualTo(
                "annotated-value");

        res = aggregated(client.get("/expect-custom-trailers"));
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.of("x-custom-header"))).isEqualTo("value");
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);
        assertThat(res.trailers().get(HttpHeaderNames.of("x-custom-trailers"))).isEqualTo("value");
        assertThat(res.trailers().get(HttpHeaderNames.of("x-custom-annotated-trailers"))).isEqualTo(
                "annotated-value");

        res = aggregated(client.get("/expect-http-file-service-headers-not-overwritten"));
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.of("x-custom-header"))).isEqualTo("value");
        assertThat(res.content().array()).isEqualTo(BYTEARRAY);
        assertThat(res.trailers().get(HttpHeaderNames.of("x-custom-trailers"))).isEqualTo("value");
        assertThat(res.headers().get(HttpHeaderNames.of("x-custom-annotated-header"))).isEqualTo(
                "annotated-value");
    }

    private static AggregatedHttpResponse aggregated(HttpResponse response) {
        return response.aggregate().join();
    }

    @Test
    void charset() {
        assertThat(StandardCharsets.UTF_8.contains(StandardCharsets.UTF_8)).isTrue();
        assertThat(StandardCharsets.UTF_8.contains(StandardCharsets.UTF_16)).isTrue();
        assertThat(StandardCharsets.UTF_16.contains(StandardCharsets.UTF_8)).isTrue();
        assertThat(StandardCharsets.UTF_16.contains(StandardCharsets.UTF_16)).isTrue();
        assertThat(StandardCharsets.UTF_8.contains(StandardCharsets.ISO_8859_1)).isTrue();
        assertThat(StandardCharsets.UTF_16.contains(StandardCharsets.ISO_8859_1)).isTrue();

        assertThat(StandardCharsets.ISO_8859_1.contains(StandardCharsets.UTF_8)).isFalse();
    }

    @Test
    void defaultNullHandling() throws JsonProcessingException {
        assertThat(new ObjectMapper().writeValueAsString(null)).isEqualTo("null");
    }

    @Test
    void jsonTextSequences_stream() {
        testJsonTextSequences("/stream");
    }

    @Test
    void jsonTextSequences_publisher() {
        testJsonTextSequences("/publisher");
    }

    private static void testJsonTextSequences(String path) {
        final BiConsumer<HttpObject, String> ensureExpectedHttpData = (o, expectedString) -> {
            assertThat(o).isInstanceOf(HttpData.class);
            final HttpData data = (HttpData) o;
            try {
                assertThat(mapper.readValue(data.array(), 1, data.length() - 2, String.class))
                        .isEqualTo(expectedString);
            } catch (IOException e) {
                // Always false.
                assertThat(e).isNull();
            }
        };

        StepVerifier.create(WebClient.of(server.httpUri() + "/json-seq").get(path))
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_SEQ))
                    .assertNext(o -> ensureExpectedHttpData.accept(o, "foo"))
                    .assertNext(o -> ensureExpectedHttpData.accept(o, "bar"))
                    .assertNext(o -> ensureExpectedHttpData.accept(o, "baz"))
                    .assertNext(o -> ensureExpectedHttpData.accept(o, "qux"))
                    .assertNext(AnnotatedServiceResponseConverterTest::assertThatLastContent)
                    .expectComplete()
                    .verify();
    }

    @Test
    void eventStream_stream() {
        testEventStream("/stream");
    }

    @Test
    void eventStream_publisher() {
        testEventStream("/publisher");
    }

    private static void testEventStream(String path) {
        StepVerifier.create(WebClient.of(server.httpUri() + "/event-stream").get(path))
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.EVENT_STREAM))
                    .expectNext(HttpData.ofUtf8("data:foo\n\n"))
                    .expectNext(HttpData.ofUtf8("data:bar\n\n"))
                    .expectNext(HttpData.ofUtf8("data:baz\n\n"))
                    .expectNext(HttpData.ofUtf8("data:qux\n\n"))
                    .assertNext(AnnotatedServiceResponseConverterTest::assertThatLastContent)
                    .expectComplete()
                    .verify();
    }

    private static void assertThatLastContent(HttpObject o) {
        // On the server side, HttpResponseSubscriber emits a DATA frame with end of stream
        // flag when the HttpResponseWriter is closed.
        final HttpData lastContent = (HttpData) o;
        assertThat(lastContent.isEmpty()).isTrue();
        assertThat(lastContent.isEndOfStream()).isTrue();
    }
}
