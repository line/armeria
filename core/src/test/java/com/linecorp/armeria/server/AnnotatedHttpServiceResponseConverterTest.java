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
package com.linecorp.armeria.server;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ProducesText;
import com.linecorp.armeria.testing.server.ServerRule;

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
                public Publisher<String> string() {
                    return new ObjectPublisher<>("¥");
                }

                @Get("/byteArray")
                public Publisher<byte[]> byteArray() {
                    return new ObjectPublisher<>("¥".getBytes());
                }

                @Get("/httpData")
                public Publisher<HttpData> httpData() {
                    return new ObjectPublisher<>(HttpData.of("¥".getBytes()));
                }

                @Get("/jsonNode")
                public Publisher<JsonNode> jsonNode() throws IOException {
                    return new ObjectPublisher<>(mapper.readTree("{\"a\":\"¥\"}"));
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
                public Publisher<String> defer() {
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
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.APPLICATION_BINARY);
        assertThat(msg.content().array()).isEqualTo("¥".getBytes());

        msg = aggregated(client.get("/httpData"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.APPLICATION_BINARY);
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
    public void unsupportedReturnType() {
        final ServerBuilder sb = new ServerBuilder();
        assertThatThrownBy(() -> {
            sb.annotatedService("/", new Object() {
                @Get("/unsupported")
                public Publisher<Publisher<Object>> unsupported() {
                    return new ObjectPublisher<>(new ObjectPublisher<>("a", "b", "c"),
                                                 new ObjectPublisher<>("d", "e", "f"));
                }
            });
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Invalid return type");
    }

    @Test
    public void produceTypeAnnotationBasedDefaultResponseConverter() throws Exception {
        final HttpClient client = HttpClient.of(rule.uri("/produce"));

        AggregatedHttpMessage msg;

        msg = aggregated(client.get("/string"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(msg.content().array()).isEqualTo("100".getBytes());

        msg = aggregated(client.get("/byteArray"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.APPLICATION_BINARY);
        assertThat(msg.content().array()).isEqualTo("¥".getBytes());

        msg = aggregated(client.get("/httpData"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.OCTET_STREAM);
        assertThat(msg.content().array()).isEqualTo("¥".getBytes());

        msg = aggregated(client.get("/jsonNode"));
        assertThat(msg.headers().contentType()).isEqualTo(MediaType.JSON_UTF_8);
        final JsonNode expected = mapper.readTree("{\"a\":\"¥\"}");
        assertThat(msg.content().array()).isEqualTo(mapper.writeValueAsBytes(expected));
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
}
