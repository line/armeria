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
package com.linecorp.armeria.internal.common.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketDecoderConfig;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketProtocolViolationException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class WebSocketFrameEncoderAndDecoderTest {

    // Forked from Netty 4.1.69 at 34a31522f0145e2d434aaea2ef8ac5ed8d1a91a0
    // - Change to use HttpRequestWriter and HttpResponseWriter instead of EmbeddedChannel.

    private static final int MAX_TEST_DATA_LENGTH = 100 * 1024;
    private static final ServiceRequestContext ctx =
            ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    private static final BlockingQueue<WebSocketFrame> frameQueue = new LinkedBlockingQueue<>();

    private static ByteBuf binaryData;
    private static String textData;

    @BeforeAll
    static void setUp() {
        binaryData = Unpooled.buffer(MAX_TEST_DATA_LENGTH);
        byte j = 0;
        for (int i = 0; i < MAX_TEST_DATA_LENGTH; i++) {
            binaryData.array()[i] = j;
            j++;
        }

        final StringBuilder s = new StringBuilder();
        char c = 'A';
        for (int i = 0; i < MAX_TEST_DATA_LENGTH; i++) {
            s.append(c);
            c++;
            if (c == 'Z') {
                c = 'A';
            }
        }
        textData = s.toString();
    }

    @AfterAll
    static void tearDown() {
        binaryData.release();
    }

    @BeforeEach
    void clear() {
        frameQueue.clear();
    }

    @Test
    public void testWebSocketProtocolViolation() throws InterruptedException {
        final int maxPayloadLength = 255;
        final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(true);
        final WebSocketFrameDecoder decoder = frameDecoder(encoder, maxPayloadLength, false, true);
        final HttpRequestWriter requestWriter = HttpRequest.streaming(RequestHeaders.of(HttpMethod.GET, "/"));
        final CompletableFuture<Void> whenComplete = new CompletableFuture<>();
        requestWriter.decode(decoder, ctx.alloc()).subscribe(subscriber(whenComplete));

        setBinaryDataWriterIndex(maxPayloadLength + 1);
        requestWriter.write(HttpData.wrap(
                encoder.encode(ctx, WebSocketFrame.ofPooledBinary(binaryData, true))));

        whenComplete.handle((unused, cause) -> {
            assertThat(cause).isInstanceOf(WebSocketProtocolViolationException.class);
            final WebSocketProtocolViolationException exception = (WebSocketProtocolViolationException) cause;
            assertThat(exception.closeStatus()).isEqualTo(WebSocketCloseStatus.MESSAGE_TOO_BIG);
            assertThat(exception.getMessage()).isEqualTo(
                    "Max frame length of " + maxPayloadLength + " has been exceeded.");
            return null;
        }).join();
    }

    private static WebSocketFrameDecoder frameDecoder(WebSocketFrameEncoder encoder, int maxPayloadLength,
                                                      boolean allowMaskMismatch, boolean maskPayload) {
        final HttpResponseWriter httpResponseWriter = HttpResponse.streaming();
        final WebSocketDecoderConfig config = WebSocketDecoderConfig.builder()
                                                                    .allowMaskMismatch(allowMaskMismatch)
                                                                    .maxFramePayloadLength(maxPayloadLength)
                                                                    .build();
        final WebSocketCloseHandler closeHandler = new WebSocketCloseHandler(ctx, httpResponseWriter, 1000);
        return new WebSocketFrameDecoder(ctx, config, httpResponseWriter, encoder, closeHandler, maskPayload);
    }

    @CsvSource({ "false, false", "false, true", "true, false", "true, true" })
    @ParameterizedTest
    public void testWebSocketEncodingAndDecoding(boolean maskPayload, boolean allowMaskMismatch)
            throws InterruptedException {
        final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(maskPayload);
        final WebSocketFrameDecoder decoder = frameDecoder(encoder, 1024 * 1024,
                                                           allowMaskMismatch, maskPayload);
        final HttpRequestWriter requestWriter = HttpRequest.streaming(RequestHeaders.of(HttpMethod.GET, "/"));
        requestWriter.decode(decoder, ctx.alloc()).subscribe(subscriber(new CompletableFuture<>()));
        executeTests(encoder, requestWriter);
    }

    private static void executeTests(WebSocketFrameEncoder encoder, HttpRequestWriter requestWriter)
            throws InterruptedException {
        // Test at the boundaries of each message type, because this shifts the position of the mask field
        // Test min. 4 lengths to check for problems related to an uneven frame length
        executeTests(encoder, requestWriter, 0);
        executeTests(encoder, requestWriter, 1);
        executeTests(encoder, requestWriter, 2);
        executeTests(encoder, requestWriter, 3);
        executeTests(encoder, requestWriter, 4);
        executeTests(encoder, requestWriter, 5);

        executeTests(encoder, requestWriter, 125);
        executeTests(encoder, requestWriter, 126);
        executeTests(encoder, requestWriter, 127);
        executeTests(encoder, requestWriter, 128);
        executeTests(encoder, requestWriter, 129);

        executeTests(encoder, requestWriter, 65535);
        executeTests(encoder, requestWriter, 65536);
        executeTests(encoder, requestWriter, 65537);
        executeTests(encoder, requestWriter, 65538);
        executeTests(encoder, requestWriter, 65539);
    }

    private static void executeTests(WebSocketFrameEncoder encoder, HttpRequestWriter requestWriter,
                                     int testDataLength) throws InterruptedException {
        testTextWithLen(encoder, requestWriter, testDataLength);
        testBinaryWithLen(encoder, requestWriter, testDataLength);
    }

    private static void testTextWithLen(WebSocketFrameEncoder encoder, HttpRequestWriter requestWriter,
                                        int testDataLength) throws InterruptedException {
        final String testStr = textData.substring(0, testDataLength);
        requestWriter.write(HttpData.wrap(encoder.encode(ctx, WebSocketFrame.ofText(testStr))));
        final WebSocketFrame decoded = frameQueue.take();
        assertThat(decoded.type()).isSameAs(WebSocketFrameType.TEXT);
        assertThat(testStr).isEqualTo(decoded.text());
        assertThat(frameQueue).isEmpty();
    }

    private static void testBinaryWithLen(WebSocketFrameEncoder encoder, HttpRequestWriter requestWriter,
                                          int testDataLength) throws InterruptedException {
        setBinaryDataWriterIndex(testDataLength);
        requestWriter.write(HttpData.wrap(encoder.encode(ctx, WebSocketFrame.ofPooledBinary(binaryData))));
        final WebSocketFrame decoded = frameQueue.take();
        assertThat(decoded.type()).isSameAs(WebSocketFrameType.BINARY);
        final ByteBuf decodedBuf = decoded.byteBuf();
        assertThat(decodedBuf.readableBytes()).isEqualTo(testDataLength);
        for (int i = 0; i < testDataLength; i++) {
            assertThat(binaryData.getByte(i)).isSameAs(decodedBuf.getByte(i));
        }
        decodedBuf.release();
    }

    private static void setBinaryDataWriterIndex(int writerIndex) {
        binaryData.retain(); // need to retain for sending and still keeping it
        binaryData.setIndex(0, writerIndex); // Send only len bytes
    }

    private static Subscriber<WebSocketFrame> subscriber(CompletableFuture<Void> whenComplete) {
        return new Subscriber<WebSocketFrame>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WebSocketFrame webSocketFrame) {
                frameQueue.add(webSocketFrame);
            }

            @Override
            public void onError(Throwable t) {
                whenComplete.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                whenComplete.complete(null);
            }
        };
    }
}
