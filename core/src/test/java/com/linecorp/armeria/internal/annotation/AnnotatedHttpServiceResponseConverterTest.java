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
package com.linecorp.armeria.internal.annotation;

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

import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.HttpResult;
import com.linecorp.armeria.server.annotation.NullToNoContentResponseConverterFunction;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ProducesJsonSequences;
import com.linecorp.armeria.server.annotation.ProducesOctetStream;
import com.linecorp.armeria.server.annotation.ProducesText;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.util.AsciiString;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class AnnotatedHttpServiceResponseConverterTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/type", new Object() {
                @Get("/string")
                public String string() {
                    return "¥";
                }

                @Get("/byteArray")
                public byte[] byteArray() {
                    return "¥".getBytes();
                }

                @Get("/httpData")
                public HttpData httpData() {
                    return HttpData.of("¥".getBytes());
                }

                @Get("/jsonNode")
                public JsonNode jsonNode() throws IOException {
                    return mapper.readTree("{\"a\":\"¥\"}");
                }
            });

            sb.annotatedService("/publish/single", new Object() {
                @Get("/string")
                @ProducesText   // Can omit this annotation, but it's not recommended.
                public Publisher<String> string() {
                    return Mono.just("¥");
                }

                @Get("/byteArray")
                @ProducesOctetStream
                public Publisher<byte[]> byteArray() {
                    return new ObjectPublisher<>("¥".getBytes());
                }

                @Get("/httpData")
                @ProducesOctetStream
                public Publisher<HttpData> httpData() {
                    return new ObjectPublisher<>(HttpData.of("¥".getBytes()));
                }

                @Get("/jsonNode")
                @ProducesJson   // Can omit this annotation, but it's not recommended.
                public Publisher<JsonNode> jsonNode() throws IOException {
                    return Mono.just(mapper.readTree("{\"a\":\"¥\"}"));
                }
            });

            sb.annotatedService("/publish/multi", new Object() {
                @Get("/string")
                @ProducesJson
                public Publisher<String> string() {
                    return new ObjectPublisher<>("a", "b", "c");
                }

                @Get("/jsonNode")
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
                @Get("/mono/jsonNode")
                public HttpResult<Publisher<JsonNode>> monoJsonNode() throws IOException {
                    return HttpResult.of(Mono.just(mapper.readTree("{\"a\":\"¥\"}")));
                }

                @Get("/jsonNode")
                @ProducesJson
                public HttpResult<Publisher<JsonNode>> jsonNode() throws IOException {
                    return HttpResult.of(new ObjectPublisher<>(mapper.readTree("{\"a\":\"¥\"}")));
                }

                @Get("/defer")
                public HttpResult<Publisher<String>> defer() {
                    return HttpResult.of(exceptionRaisingPublisher());
                }
            });

            sb.annotatedService("/produce", new Object() {
                @Get("/string")
                @ProducesText
                public int string() {
                    return 100;
                }

                @Get("/byteArray")
                @UserProduceBinary
                public byte[] byteArray() {
                    return "¥".getBytes();
                }

                @Get("/httpData")
                @Produces("application/octet-stream")
                public HttpData httpData() {
                    return HttpData.of("¥".getBytes());
                }

                @Get("/jsonNode")
                @ProducesJson
                public Map<String, String> jsonNode() throws IOException {
                    return ImmutableMap.of("a", "¥");
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
                @ProducesJson
                public HttpResult<Map<String, String>> expectCustomHeader() {
                    return HttpResult.of(HttpHeaders.of(AsciiString.of("x-custom-header"), "value"),
                                         ImmutableMap.of("a", "b"));
                }

                @Get("/expect-custom-trailing-header")
                @ProducesJson
                public HttpResult<List<String>> expectCustomTrailingHeader() {
                    return HttpResult.of(HttpHeaders.of(AsciiString.of("x-custom-header"), "value"),
                                         ImmutableList.of("a", "b"),
                                         HttpHeaders.of(AsciiString.of("x-custom-trailing-header"), "value"));
                }

                @Get("/async/expect-custom-header")
                @ProducesJson
                public HttpResult<CompletionStage<Map<String, String>>> asyncExpectCustomHeader() {
                    return HttpResult.of(HttpHeaders.of(AsciiString.of("x-custom-header"), "value"),
                                         CompletableFuture.completedFuture(ImmutableMap.of("a", "b")));
                }

                @Get("/async/expect-custom-trailing-header")
                @ProducesJson
                public HttpResult<CompletionStage<List<String>>> asyncExpectCustomTrailingHeader(
                        ServiceRequestContext ctx) {
                    final CompletableFuture<List<String>> future = new CompletableFuture<>();
                    ctx.eventLoop().schedule(() -> future.complete(ImmutableList.of("a", "b")),
                                             1, TimeUnit.SECONDS);
                    return HttpResult.of(HttpHeaders.of(AsciiString.of("x-custom-header"), "value"),
                                         future,
                                         HttpHeaders.of(AsciiString.of("x-custom-trailing-header"), "value"));
                }

                @Get("/async/expect-bad-request")
                public HttpResult<CompletionStage<Object>> asyncExpectBadRequest() {
                    final CompletableFuture<Object> future = new CompletableFuture<>();
                    future.completeExceptionally(new IllegalArgumentException("Bad arguments"));
                    return HttpResult.of(HttpHeaders.of(HttpStatus.OK), future);
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

    private static class ObjectPublisher<T> implements Publisher<T> {
        private final List<T> objects;

        ObjectPublisher(T... objects) {
            this.objects = ImmutableList.copyOf(objects);
        }

        @Override
        public void subscribe(Subscriber<? super T> s) {
            s.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    final int size = objects.size();
                    assert n >= size;
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

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @Produces("application/binary")
    @Produces("application/octet-stream")
    @interface UserProduceBinary {}

    @Test
    public void typeBasedDefaultResponseConverter() throws Exception {
        shouldBeConvertedByDefaultResponseConverter(HttpClient.of(rule.uri("/type")));
    }

    @Test
    public void publisherBasedResponseConverter() throws Exception {
        shouldBeConvertedByDefaultResponseConverter(HttpClient.of(rule.uri("/publish/single")));
    }

    private void shouldBeConvertedByDefaultResponseConverter(HttpClient client) throws Exception {
        AggregatedHttpMessage msg;

        msg = aggregated(client.get("/string"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(msg.content().toStringUtf8()).isEqualTo("¥");
        assertThat(msg.content().toStringAscii()).isNotEqualTo("¥");

        msg = aggregated(client.get("/byteArray"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.OCTET_STREAM);
        assertThat(msg.content().array()).isEqualTo("¥".getBytes());

        msg = aggregated(client.get("/httpData"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.OCTET_STREAM);
        assertThat(msg.content().array()).isEqualTo("¥".getBytes());

        msg = aggregated(client.get("/jsonNode"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        final JsonNode expected = mapper.readTree("{\"a\":\"¥\"}");
        assertThat(msg.content().array()).isEqualTo(mapper.writeValueAsBytes(expected));
    }

    @Test
    public void multipleObjectPublisherBasedResponseConverter() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/publish/multi"));

        AggregatedHttpMessage msg;

        msg = aggregated(client.get("/string"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(msg.content().toStringUtf8())
                .isArray().ofLength(3)
                .thatContains("a").thatContains("b").thatContains("c");

        msg = aggregated(client.get("/jsonNode"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(msg.content().toStringUtf8())
                .isEqualTo("[{\"a\":\"1\"},{\"b\":\"2\"},{\"c\":\"3\"}]");
    }

    @Test
    public void publisherBasedResponseConversionFailure() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/publish/failure"));

        AggregatedHttpMessage msg;

        msg = aggregated(client.get("/immediate"));
        assertThat(msg.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        msg = aggregated(client.get("/defer"));
        assertThat(msg.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    public void produceTypeAnnotationBasedDefaultResponseConverter() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/produce"));

        AggregatedHttpMessage msg;

        msg = aggregated(client.get("/string"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(msg.content().array()).isEqualTo("100".getBytes());

        msg = aggregated(client.get("/byteArray"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.OCTET_STREAM);
        assertThat(msg.content().array()).isEqualTo("¥".getBytes());

        msg = aggregated(client.get("/httpData"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.OCTET_STREAM);
        assertThat(msg.content().array()).isEqualTo("¥".getBytes());

        msg = aggregated(client.get("/jsonNode"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        final JsonNode expected = mapper.readTree("{\"a\":\"¥\"}");
        assertThat(msg.content().array()).isEqualTo(mapper.writeValueAsBytes(expected));
    }

    @Test
    public void customizedHttpResponse() {
        final HttpClient client = HttpClient.of(rule.uri("/custom-response"));

        AggregatedHttpMessage msg;

        msg = aggregated(client.get("/expect-specified-status"));
        assertThat(msg.status()).isEqualTo(HttpStatus.ACCEPTED);

        msg = aggregated(client.get("/expect-no-content"));
        assertThat(msg.status()).isEqualTo(HttpStatus.NO_CONTENT);

        msg = aggregated(client.get("/expect-ok"));
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);

        msg = aggregated(client.get("/expect-specified-no-content"));
        assertThat(msg.status()).isEqualTo(HttpStatus.NO_CONTENT);

        msg = aggregated(client.get("/expect-not-modified"));
        assertThat(msg.status()).isEqualTo(HttpStatus.NOT_MODIFIED);

        msg = aggregated(client.get("/expect-unauthorized"));
        assertThat(msg.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        msg = aggregated(client.get("/expect-no-content-from-converter"));
        assertThat(msg.status()).isEqualTo(HttpStatus.NO_CONTENT);

        ImmutableList.of("/expect-custom-header",
                         "/async/expect-custom-header").forEach(path -> {
            final AggregatedHttpMessage message = aggregated(client.get(path));
            assertThat(message.status()).isEqualTo(HttpStatus.OK);
            assertThat(message.headers().get(AsciiString.of("x-custom-header"))).isEqualTo("value");
            assertThatJson(message.content().toStringUtf8()).isEqualTo(ImmutableMap.of("a", "b"));
        });

        ImmutableList.of("/expect-custom-trailing-header",
                         "/async/expect-custom-trailing-header").forEach(path -> {
            final AggregatedHttpMessage message = aggregated(client.get(path));
            assertThat(message.status()).isEqualTo(HttpStatus.OK);
            assertThat(message.headers().get(AsciiString.of("x-custom-header"))).isEqualTo("value");
            assertThatJson(message.content().toStringUtf8()).isEqualTo(ImmutableList.of("a", "b"));
            assertThat(message.trailingHeaders().get(AsciiString.of("x-custom-trailing-header")))
                    .isEqualTo("value");
        });

        msg = aggregated(client.get("/async/expect-bad-request"));
        assertThat(msg.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        ImmutableList.of("/wildcard", "/generic").forEach(path -> {
            final AggregatedHttpMessage message = aggregated(client.get(path));
            assertThat(message.status()).isEqualTo(HttpStatus.OK);
            assertThatJson(message.content().toStringUtf8()).isEqualTo(ImmutableList.of("a", "b"));
        });
    }

    @Test
    public void httpResultWithPublisher() {
        final HttpClient client = HttpClient.of(rule.uri("/publish/http-result"));

        AggregatedHttpMessage msg;

        msg = aggregated(client.get("/mono/jsonNode"));
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(msg.content().toStringUtf8()).isEqualTo(ImmutableMap.of("a", "¥"));

        msg = aggregated(client.get("/jsonNode"));
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(msg.content().toStringUtf8())
                .isEqualTo(ImmutableList.of(ImmutableMap.of("a", "¥")));

        msg = aggregated(client.get("/defer"));
        assertThat(msg.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static AggregatedHttpMessage aggregated(HttpResponse response) {
        return response.aggregate().join();
    }

    @Test
    public void charset() {
        assertThat(StandardCharsets.UTF_8.contains(StandardCharsets.UTF_8)).isTrue();
        assertThat(StandardCharsets.UTF_8.contains(StandardCharsets.UTF_16)).isTrue();
        assertThat(StandardCharsets.UTF_16.contains(StandardCharsets.UTF_8)).isTrue();
        assertThat(StandardCharsets.UTF_16.contains(StandardCharsets.UTF_16)).isTrue();
        assertThat(StandardCharsets.UTF_8.contains(StandardCharsets.ISO_8859_1)).isTrue();
        assertThat(StandardCharsets.UTF_16.contains(StandardCharsets.ISO_8859_1)).isTrue();

        assertThat(StandardCharsets.ISO_8859_1.contains(StandardCharsets.UTF_8)).isFalse();
    }

    @Test
    public void defaultNullHandling() throws JsonProcessingException {
        assertThat(new ObjectMapper().writeValueAsString(null)).isEqualTo("null");
    }

    @Test
    public void jsonTextSequences_stream() {
        testJsonTextSequences("/json-seq/stream");
    }

    @Test
    public void jsonTextSequences_publisher() {
        testJsonTextSequences("/json-seq/publisher");
    }

    private void testJsonTextSequences(String path) {
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

        StepVerifier.create(HttpClient.of(rule.uri("/")).get(path))
                    .assertNext(o -> {
                        assertThat(o).isInstanceOf(HttpHeaders.class);
                        final HttpHeaders headers = (HttpHeaders) o;
                        assertThat(headers.status()).isEqualTo(HttpStatus.OK);
                        assertThat(headers.contentType()).isEqualTo(MediaType.JSON_SEQ);
                    })
                    .assertNext(o -> ensureExpectedHttpData.accept(o, "foo"))
                    .assertNext(o -> ensureExpectedHttpData.accept(o, "bar"))
                    .assertNext(o -> ensureExpectedHttpData.accept(o, "baz"))
                    .assertNext(o -> ensureExpectedHttpData.accept(o, "qux"))
                    .assertNext(o -> {
                        // On the server side, HttpResponseSubscriber emits a DATA frame with end of stream
                        // flag when the HttpResponseWriter is closed.
                        final HttpData lastContent = (HttpData) o;
                        assertThat(lastContent.isEmpty()).isTrue();
                        assertThat(lastContent.isEndOfStream()).isTrue();
                    })
                    .expectComplete()
                    .verify();
    }
}
