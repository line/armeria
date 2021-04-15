package com.linecorp.armeria.server.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.stream.Stream;

import org.junit.ClassRule;
import org.junit.Test;

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
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

public class JsonLinesTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    @ClassRule
    public static ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/seq/publisher",
                    (ctx, req) -> JsonLines.fromPublisher(Flux.just("foo", "bar", "baz", "qux")))
                    .service("/seq/stream",
                            (ctx, req) -> JsonLines.fromStream(
                                    Stream.of("foo", "bar", "baz", "qux"), MoreExecutors.directExecutor()))
                    .service("/seq/custom-mapper",
                            (ctx, req) -> JsonLines.fromPublisher(
                                    Flux.just("foo", "bar", "baz", "qux"),
                                    new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)))
                    .service("/seq/single",
                            (ctx, req) -> JsonLines.fromObject("foo"));
            sb.disableServerHeader();
            sb.disableDateHeader();
        }
    };

    @Test
    public void fromPublisherOrStream() {
        final WebClient client = WebClient.of(rule.httpUri() + "/seq");
        for (final String path : ImmutableList.of("/publisher", "/stream", "/custom-mapper")) {
            final HttpResponse response = client.get(path);
            StepVerifier.create(response)
                    .expectNext(ResponseHeaders.of(HttpStatus.OK,
                            HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_LINES))
                    .assertNext(o -> ensureExpectedHttpData(o, "foo"))
                    .assertNext(o -> ensureExpectedHttpData(o, "bar"))
                    .assertNext(o -> ensureExpectedHttpData(o, "baz"))
                    .assertNext(o -> ensureExpectedHttpData(o, "qux"))
                    .assertNext(JsonLinesTest::assertThatLastContent)
                    .expectComplete()
                    .verify();
        }
    }

    @Test
    public void singleSequence() {
        final AggregatedHttpResponse response =
                WebClient.of(rule.httpUri() + "/seq").get("/single").aggregate().join();
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

    private static void ensureExpectedHttpData(HttpObject o, String expectedString) {
        assertThat(o).isInstanceOf(HttpData.class);
        final HttpData data = (HttpData) o;
        try {
            assertThat(mapper.readValue(data.array(), 0, data.length() - 1, String.class))
                    .isEqualTo(expectedString);
        } catch (IOException e) {
            // Always false.
            assertThat(e).isNull();
        }
    }
}
