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

package com.linecorp.armeria.common.grpc.protocol;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.grpc.ForwardingDecompressor;
import com.linecorp.armeria.internal.common.grpc.GrpcTestUtil;

import io.grpc.Codec.Gzip;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import reactor.test.StepVerifier;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.SimpleRequest;

class ArmeriaMessageDeframerTest {

    private static final int MAX_MESSAGE_SIZE = 1024;

    private DeframedMessage deframedMessage;

    @BeforeEach
    void setUp() {
        deframedMessage = new DeframedMessage(GrpcTestUtil.requestByteBuf(), 0);
    }

    @AfterEach
    void tearDown() throws Exception {
        deframedMessage.buf().release();
    }

    @Test
    void request_noDataYet() {
        final StreamWriter<HttpData> source = StreamMessage.streaming();
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(source, false);
        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .expectNextCount(0)
                    .verifyTimeout(Duration.ofMillis(100));
    }

    @ArgumentsSource(DeframerProvider.class)
    @ParameterizedTest
    void deframe_noRequests(boolean base64, byte[] data) {
        if (base64) {
            data = Base64.getEncoder().encode(data);
        }

        final StreamMessage<HttpData> source = newStreamMessage(data);
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(source, base64);

        StepVerifier.create(deframed)
                    .expectNextCount(0)
                    .thenRequest(1)
                    .expectNextMatches(compareAndRelease(deframedMessage))
                    .verifyComplete();
    }

    @ArgumentsSource(DeframerProvider.class)
    @ParameterizedTest
    void deframe_hasRequests(boolean base64, byte[] data) {
        final HttpData httpData;
        if (base64) {
            httpData = HttpData.wrap(Base64.getEncoder().encode(data));
        } else {
            httpData = HttpData.wrap(data);
        }
        final StreamWriter<HttpData> source = StreamMessage.streaming();
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(source, base64);
        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .then(() -> {
                        source.write(httpData);
                        source.close();
                    })
                    .expectNextMatches(compareAndRelease(deframedMessage))
                    .verifyComplete();
    }

    @ArgumentsSource(DeframerProvider.class)
    @ParameterizedTest
    void deframe_frameWithManyFragments(boolean base64, byte[] data) {
        final List<byte[]> fragments;
        if (base64) {
            fragments = base64EncodedFragments(data);
        } else {
            fragments = Bytes.asList(data).stream()
                             .map(aByte -> new byte[]{ aByte })
                             .collect(toImmutableList());
        }

        final StreamWriter<HttpData> source = StreamMessage.streaming();
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(source, base64);

        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .then(() -> {
                        // Only the last fragment should notify the listener.
                        for (int i = 0; i < fragments.size() - 1; i++) {
                            source.write(HttpData.wrap(fragments.get(i)));
                        }
                    })
                    .expectNextCount(0)
                    .then(() -> {
                        source.write(HttpData.wrap(fragments.get(fragments.size() - 1)));
                        source.close();
                    })
                    .expectNextMatches(compareAndRelease(deframedMessage))
                    .verifyComplete();
    }

    @ArgumentsSource(DeframerProvider.class)
    @ParameterizedTest
    void deframe_frameWithHeaderAndBodyFragment(boolean base64, byte[] data) {
        final StreamWriter<HttpData> source = StreamMessage.streaming();
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(source, base64);

        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .then(() -> {
                        // Frame is split into two fragments - header and body.
                        byte[] src = Arrays.copyOfRange(data, 0, 5);
                        if (base64) {
                            src = Base64.getEncoder().encode(src);
                        }
                        source.write(HttpData.wrap(src));
                    })
                    .expectNextCount(0)
                    .then(() -> {
                        byte[] src = Arrays.copyOfRange(data, 5, data.length);
                        if (base64) {
                            src = Base64.getEncoder().encode(src);
                        }
                        source.write(HttpData.wrap(src));
                        source.close();
                    })
                    .expectNextMatches(compareAndRelease(deframedMessage))
                    .verifyComplete();
    }

    @ArgumentsSource(DeframerProvider.class)
    @ParameterizedTest
    void deframe_multipleMessagesBeforeRequests(boolean base64, byte[] data) {
        if (base64) {
            data = Base64.getEncoder().encode(data);
        }

        final StreamMessage<HttpData> source = newStreamMessage(data, data);
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(source, base64);

        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .expectNextMatches(compareAndRelease(deframedMessage))
                    .thenRequest(1)
                    .expectNextMatches(compareAndRelease(deframedMessage))
                    .verifyComplete();
    }

    @ArgumentsSource(DeframerProvider.class)
    @ParameterizedTest
    void deframe_multipleMessagesAfterRequests(boolean base64, byte[] data) {
        final byte[] maybeEncoded = base64 ? Base64.getEncoder().encode(data) : data;
        final StreamWriter<HttpData> source = StreamMessage.streaming();
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(source, base64);

        StepVerifier.create(deframed)
                    .thenRequest(2)
                    .then(() -> {
                        source.write(HttpData.wrap(maybeEncoded));
                        source.write(HttpData.wrap(maybeEncoded));
                        source.close();
                    })
                    .expectNextMatches(compareAndRelease(deframedMessage))
                    .expectNextMatches(compareAndRelease(deframedMessage))
                    .verifyComplete();
    }

    @ArgumentsSource(DeframerProvider.class)
    @ParameterizedTest
    void deframe_endOfStream(boolean base64) throws Exception {
        final StreamMessage<HttpData> empty = StreamMessage.of();
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(empty, base64);

        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .verifyComplete();
    }

    private static StreamMessage<DeframedMessage> newDeframedStreamMessage(StreamMessage<HttpData> source,
                                                                           boolean base64) {
        final UnpooledByteBufAllocator alloc = UnpooledByteBufAllocator.DEFAULT;
        final ArmeriaMessageDeframer deframer =
                new ArmeriaMessageDeframer(MAX_MESSAGE_SIZE, alloc, base64)
                        .decompressor(ForwardingDecompressor.forGrpc(new Gzip()));
        return source.decode(deframer, alloc);
    }

    @ArgumentsSource(DeframerProvider.class)
    @ParameterizedTest
    void deframe_compressed(boolean base64) throws Exception {
        byte[] data = GrpcTestUtil.compressedFrame(GrpcTestUtil.requestByteBuf());
        if (base64) {
            data = Base64.getEncoder().encode(data);
        }

        final StreamMessage<HttpData> source = newStreamMessage(data);
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(source, base64);

        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .expectNextMatches(message -> {
                        assertThat(message.stream()).isNotNull();
                        byte[] messageBytes = null;
                        try (InputStream stream = message.stream()) {
                            messageBytes = ByteStreams.toByteArray(stream);
                        } catch (IOException e) {
                            Exceptions.throwUnsafely(e);
                        }
                        assertThat(messageBytes).isEqualTo(GrpcTestUtil.REQUEST_MESSAGE.toByteArray());
                        return true;
                    })
                    .verifyComplete();
    }

    @Test
    void deframe_tooLargeUncompressed() {
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setPayload(Payload.newBuilder()
                                                                      .setBody(ByteString.copyFromUtf8(
                                                                              Strings.repeat("a", 1024))))
                                                   .build();
        final byte[] frame = GrpcTestUtil.uncompressedFrame(Unpooled.wrappedBuffer(request.toByteArray()));
        assertThat(frame.length).isGreaterThan(1024);

        final StreamMessage<HttpData> source = newStreamMessage(frame);
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(source, false);

        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .expectError(ArmeriaStatusException.class)
                    .verify();
    }

    @Test
    void deframe_tooLargeCompressed() {
        // Simple repeated character compresses below the frame threshold but uncompresses above it.
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(
                                                        Strings.repeat("a", 1024))))
                             .build();
        final byte[] frame = GrpcTestUtil.compressedFrame(Unpooled.wrappedBuffer(request.toByteArray()));
        assertThat(frame.length).isLessThan(1024);

        final StreamMessage<HttpData> source = newStreamMessage(frame);
        final StreamMessage<DeframedMessage> deframed = newDeframedStreamMessage(source, false);

        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .expectNextMatches(message -> {
                        try (InputStream stream = message.stream()) {
                            assertThatThrownBy(() -> ByteStreams.toByteArray(stream))
                                    .isInstanceOf(ArmeriaStatusException.class);
                        } catch (IOException e) {
                            Exceptions.throwUnsafely(e);
                        }
                        return true;
                    })
                    .verifyComplete();
    }

    private static ArrayList<byte[]> base64EncodedFragments(byte[] frameBytes) {
        final ArrayList<byte[]> fragments = new ArrayList<>();
        for (int i = 0; i < frameBytes.length;) {
            // One byte is selected at least.
            final int to = Math.min(frameBytes.length, new Random().nextInt(5) + 1 + i);
            final byte[] encoded = Base64.getEncoder().encode(Arrays.copyOfRange(frameBytes, i, to));
            fragments.add(encoded);
            i = to;
        }
        return fragments;
    }

    private static StreamMessage<HttpData> newStreamMessage(byte[] data) {
        return StreamMessage.of(HttpData.wrap(data));
    }

    private static StreamMessage<HttpData> newStreamMessage(byte[] data1, byte[] data2) {
        return StreamMessage.of(HttpData.wrap(data1), HttpData.wrap(data2));
    }

    private static final class DeframerProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(true, false)
                         .map(decodeBase64 -> {
                             final byte[] data = GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf());
                             return Arguments.of(decodeBase64, data);
                         });
        }
    }

    private static Predicate<DeframedMessage> compareAndRelease(DeframedMessage second) {
        return first -> {
            final boolean result = first.equals(second);
            first.buf().release();
            return result;
        };
    }
}
