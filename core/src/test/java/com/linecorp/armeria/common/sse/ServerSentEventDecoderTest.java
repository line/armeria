/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.streaming.ServerSentEvents;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import reactor.test.StepVerifier;

class ServerSentEventDecoderTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/events", (ctx, req) -> {
                final ServerSentEvent dataEvent =
                        ServerSentEvent.builder()
                                       .id("1")
                                       .event("update")
                                       .data("hello\nworld")
                                       .build();
                final StreamMessage<ServerSentEvent> events = StreamMessage.of(
                        dataEvent,
                        ServerSentEvent.ofComment("keepalive"),
                        ServerSentEvent.ofRetry(Duration.ofMillis(1000)));

                return ServerSentEvents.fromPublisher(events);
            });
        }
    };

    @ParameterizedTest
    @ValueSource(strings = { "\n", "\r\n", "\r" })
    void handlesEverySplitPoint(String newline) {
        final String wire = "\uFEFFid: 1" + newline + "data: 한글😀" + newline + newline +
                            ": keepalive" + newline + newline + "data:" + newline + newline;
        final List<ServerSentEvent> expected = Arrays.asList(
                ServerSentEvent.builder().id("1").data("한글😀").build(),
                ServerSentEvent.ofComment("keepalive"),
                ServerSentEvent.ofData(""));
        assertDecodedAtEverySplit(wire, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = { "\n", "\r\n", "\r" })
    void ignoresBomOnlyFirstLine(String newline) {
        assertDecodedAtEverySplit("\uFEFF" + newline + "data: first" + newline + newline,
                                 Arrays.asList(ServerSentEvent.ofData("first")));
    }

    @Test
    void handlesMixedLineEndings() {
        assertDecodedAtEverySplit(
                "\uFEFFid: 1\r\ndata: first\n\r\n:keepalive\r\n\rdata:second\r\r\n",
                Arrays.asList(
                        ServerSentEvent.builder().id("1").data("first").build(),
                        ServerSentEvent.ofComment("keepalive"),
                        ServerSentEvent.ofData("second")));
    }

    @ParameterizedTest
    @ValueSource(strings = { "\n", "\r\n", "\r" })
    void distinguishesMultilineDataFromSeparateEvents(String newline) {
        assertDecodedAtEverySplit("data: a" + newline + "data: b" + newline + newline,
                                 Arrays.asList(ServerSentEvent.ofData("a\nb")));
        assertDecodedAtEverySplit(
                "data: a" + newline + newline + "data: b" + newline + newline,
                Arrays.asList(
                        ServerSentEvent.ofData("a"),
                        ServerSentEvent.ofData("b")));
    }

    @Test
    void ignoresEmptyAndUnknownBlocksAndDiscardsUnterminatedEvent() {
        assertThat(decode("\n\r\nunknown: ignored\r\rdata: complete\n\ndata: partial\n"))
                .containsExactly(ServerSentEvent.ofData("complete"));
        assertThat(decode("data: partial")).isEmpty();
        assertThat(decode("data: partial\r\n")).isEmpty();
    }

    @Test
    void preservesMetadataBlocksWithoutInheritingFields() {
        assertThat(decode("id: 42\n\nretry: 0\n\nevent: update\n\ndata: next\n\n"))
                .containsExactly(
                        ServerSentEvent.ofId("42"),
                        ServerSentEvent.ofRetry(Duration.ZERO),
                        ServerSentEvent.ofEvent("update"),
                        ServerSentEvent.ofData("next"));
    }

    @Test
    void onlyIgnoresBomAtStreamStart() {
        assertThat(decode("\uFEFFdata: first\n\n\uFEFFdata: ignored\n\ndata: \uFEFFkept\n\n"))
                .containsExactly(
                        ServerSentEvent.ofData("first"),
                        ServerSentEvent.ofData("\uFEFFkept"));
        assertThat(decode("\n\uFEFFdata: ignored\n\n")).isEmpty();
        assertThat(decode("\uFEFF\n\n\uFEFFdata: ignored\n\ndata: next\n\n"))
                .containsExactly(ServerSentEvent.ofData("next"));
    }

    @Test
    void releasesPooledInput() {
        final ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer();
        buffer.writeCharSequence("data: hello\n\n", StandardCharsets.UTF_8);
        final StreamMessage<HttpData> source = StreamMessage.of(HttpData.wrap(buffer));
        final StreamMessage<ServerSentEvent> decoded = source.decode(ServerSentEvent.newDecoder());
        final List<ServerSentEvent> events = decoded.collect().join();

        assertThat(events).containsExactly(ServerSentEvent.ofData("hello"));
        assertThat(buffer.refCnt()).isZero();
    }

    @Test
    void honorsDemand() {
        final HttpData data = HttpData.ofUtf8("data: first\n\ndata: second\n\n");
        final StreamMessage<HttpData> source = StreamMessage.of(data);
        final StreamMessage<ServerSentEvent> decoded = source.decode(ServerSentEvent.newDecoder());

        StepVerifier.create(decoded, 0)
                    .thenRequest(1)
                    .expectNext(ServerSentEvent.ofData("first"))
                    .thenRequest(1)
                    .expectNext(ServerSentEvent.ofData("second"))
                    .verifyComplete();
    }

    @Test
    void propagatesUpstreamFailure() {
        final AnticipatedException cause = new AnticipatedException();
        final StreamMessage<HttpData> source = StreamMessage.aborted(cause);
        final StreamMessage<ServerSentEvent> decoded = source.decode(ServerSentEvent.newDecoder());

        StepVerifier.create(decoded)
                    .expectErrorSatisfies(error -> assertThat(error).isSameAs(cause))
                    .verify();
    }

    @Test
    void decodesRealHttpResponse() {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpResponse response = client.get("/events");
        final StreamMessage<ServerSentEvent> decoded = response.decode(ServerSentEvent.newDecoder());
        final List<ServerSentEvent> events = decoded.collect().join();
        final ServerSentEvent expectedEvent =
                ServerSentEvent.builder()
                               .id("1")
                               .event("update")
                               .data("hello\nworld")
                               .build();

        assertThat(events).containsExactly(
                expectedEvent,
                ServerSentEvent.ofComment("keepalive"),
                ServerSentEvent.ofRetry(Duration.ofMillis(1000)));
    }

    private static List<ServerSentEvent> decode(String wire) {
        final StreamMessage<HttpData> source = StreamMessage.of(HttpData.ofUtf8(wire));
        final StreamMessage<ServerSentEvent> decoded = source.decode(ServerSentEvent.newDecoder());
        return decoded.collect().join();
    }

    private static void assertDecodedAtEverySplit(String wire, List<ServerSentEvent> expected) {
        final byte[] encoded = wire.getBytes(StandardCharsets.UTF_8);
        for (int split = 0; split <= encoded.length; split++) {
            final HttpData first = HttpData.copyOf(encoded, 0, split);
            final HttpData second = HttpData.copyOf(encoded, split, encoded.length - split);
            final StreamMessage<HttpData> source = StreamMessage.of(first, second);
            final StreamMessage<ServerSentEvent> decoded = source.decode(ServerSentEvent.newDecoder());
            final List<ServerSentEvent> events = decoded.collect().join();

            assertThat(events).as("split at byte %s", split).containsExactlyElementsOf(expected);
        }

        final List<HttpData> bytes = new ArrayList<>();
        for (final byte value : encoded) {
            bytes.add(HttpData.wrap(new byte[] { value }));
        }
        final StreamMessage<HttpData> source = StreamMessage.of(bytes.toArray(new HttpData[0]));
        final StreamMessage<ServerSentEvent> decoded = source.decode(ServerSentEvent.newDecoder());
        final List<ServerSentEvent> events = decoded.collect().join();

        assertThat(events).containsExactlyElementsOf(expected);
    }
}
