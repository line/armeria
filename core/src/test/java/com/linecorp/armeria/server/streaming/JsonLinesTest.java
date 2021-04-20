package com.linecorp.armeria.server.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

public class JsonLinesTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {

        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/seq/publisher",
                    (ctx, req) -> JsonLines.fromPublisher(Flux.just("foo", "bar", "baz", "qux")))
                    .service("/seq/stream",
                            (ctx, req) -> JsonLines.fromStream(
                                    Stream.of("foo", "bar", "baz", "qux"), MoreExecutors.directExecutor()))
                    .service("/seq/custom-mapper",
                            (ctx, req) -> JsonLines.fromPublisher(
                                    Flux.just(new Pojo("Jon D", 21, Arrays.asList("Bmw", "Audi")),
                                              new Pojo("Sarah D", 22, Arrays.asList("Tesla", "Honda"))),
                                    new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)))
                    .service("/seq/single",
                            (ctx, req) -> JsonLines.fromObject("foo"));
            sb.disableServerHeader();
            sb.disableDateHeader();
        }
    };

    @Test
    public void fromPublisherOrStreamMultiLineJson() {
        final WebClient client = WebClient.of(server.httpUri() + "/seq");
        for (final String path : ImmutableList.of("/custom-mapper")) {
            final HttpResponse response = client.get(path);
            StepVerifier.create(response)
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                            HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_LINES))
                    .assertNext(o ->
                            ensureExpectedHttpData(o, "{\"name\":\"Jon D\",\"age\":21,\"cars\":[\"Bmw\",\"Audi\"]}\n", true))
                    .assertNext(o ->
                            ensureExpectedHttpData(o, "{\"name\":\"Sarah D\",\"age\":22,\"cars\":[\"Tesla\",\"Honda\"]}\n", true))
                    .assertNext(JsonLinesTest::assertThatLastContent)
                    .expectComplete()
                    .verify();
        }
    }

    @Test
    public void fromPublisherOrStream() {
        final WebClient client = WebClient.of(server.httpUri() + "/seq");
        for (final String path : ImmutableList.of("/publisher", "/stream")) {
            final HttpResponse response = client.get(path);
            StepVerifier.create(response)
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                            HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_LINES))
                    .assertNext(o -> ensureExpectedHttpData(o, "foo", false))
                    .assertNext(o -> ensureExpectedHttpData(o, "bar", false))
                    .assertNext(o -> ensureExpectedHttpData(o, "baz", false))
                    .assertNext(o -> ensureExpectedHttpData(o, "qux", false))
                    .assertNext(JsonLinesTest::assertThatLastContent)
                    .expectComplete()
                    .verify();
        }
    }

    @Test
    public void singleSequence() {
        final AggregatedHttpResponse response =
                WebClient.of(server.httpUri() + "/seq").get("/single").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().contentType()).isEqualTo(MediaType.JSON_LINES);
        // Check whether the content is serialized as a JSON Line format.
        assertThat(response.content().array()).containsExactly('"', 'f', 'o', 'o', '"', 0x0A);
    }

    private static void assertThatLastContent(HttpObject o) {
        // On the server side, HttpResponseSubscriber emits a DATA frame with end of stream
        // flag when the HttpResponseWriter is closed.
        final HttpData lastContent = (HttpData) o;
        assertThat(lastContent.isEmpty()).isTrue();
        assertThat(lastContent.isEndOfStream()).isTrue();
    }

    private static void ensureExpectedHttpData(HttpObject o, String expectedString, boolean isJsonObject) {
        assertThat(o).isInstanceOf(HttpData.class);
        final HttpData data = (HttpData) o;
        try {
            if (isJsonObject) {
                assertThat(new String(((HttpData) o).array()))
                        .isEqualTo(expectedString);
            } else {
                assertThat(mapper.readValue(data.array(), 0, data.length() - 1, String.class))
                        .isEqualTo(expectedString);
            }
        } catch (Exception e) {
            // Always false.
            assertThat(e).isNull();
        }
    }

    private static class Pojo {
        @JsonProperty("name")
        private final String name;
        @JsonProperty("age")
        private final int age;
        @JsonProperty("cars")
        private List<String> cars;

        Pojo(String n, int a, List<String> c) {
            name = n;
            age = a;
            cars = c;
        }
    }
}
