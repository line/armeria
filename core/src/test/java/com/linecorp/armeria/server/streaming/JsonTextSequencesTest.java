/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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

class JsonTextSequencesTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/seq/publisher",
                       (ctx, req) -> JsonTextSequences.fromPublisher(Flux.just("foo", "bar", "baz", "qux")))
              .service("/seq/stream",
                       (ctx, req) -> JsonTextSequences.fromStream(
                               Stream.of("foo", "bar", "baz", "qux"), MoreExecutors.directExecutor()))
              .service("/seq/custom-mapper",
                       (ctx, req) -> JsonTextSequences.fromPublisher(
                               Flux.just("foo", "bar", "baz", "qux"),
                               new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)))
              .service("/seq/single",
                       (ctx, req) -> JsonTextSequences.fromObject("foo"));
            sb.disableServerHeader();
            sb.disableDateHeader();
        }
    };

    @Test
    void fromPublisherOrStream() {
        final WebClient client = WebClient.of(server.httpUri() + "/seq");
        for (final String path : ImmutableList.of("/publisher", "/stream", "/custom-mapper")) {
            final HttpResponse response = client.get(path);
            StepVerifier.create(response)
                        .expectNext(ResponseHeaders.of(HttpStatus.OK,
                                                       HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_SEQ))
                        .assertNext(o -> ensureExpectedHttpData(o, "foo"))
                        .assertNext(o -> ensureExpectedHttpData(o, "bar"))
                        .assertNext(o -> ensureExpectedHttpData(o, "baz"))
                        .assertNext(o -> ensureExpectedHttpData(o, "qux"))
                        .assertNext(JsonTextSequencesTest::assertThatLastContent)
                        .expectComplete()
                        .verify();
        }
    }

    @Test
    void singleSequence() {
        final AggregatedHttpResponse response =
                WebClient.of(server.httpUri() + "/seq").get("/single").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().contentType()).isEqualTo(MediaType.JSON_SEQ);
        // Check whether the content is serialized as a JSON Text Sequence format.
        assertThat(response.content().array()).containsExactly(0x1E, '"', 'f', 'o', 'o', '"', 0x0A);
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
            assertThat(mapper.readValue(data.array(), 1, data.length() - 2, String.class))
                    .isEqualTo(expectedString);
        } catch (IOException e) {
            // Always false.
            assertThat(e).isNull();
        }
    }
}
