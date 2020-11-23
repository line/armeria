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

package com.linecorp.armeria.internal.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.stream.HttpDeframer;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.grpc.DecompressorRegistry;
import io.grpc.Status;
import io.netty.buffer.ByteBufAllocator;
import reactor.test.StepVerifier;

class HttpStreamDeframerTest {

    private static final ResponseHeaders HEADERS = ResponseHeaders.of(HttpStatus.OK);
    private static final HttpHeaders TRAILERS = HttpHeaders.of(GrpcHeaderNames.GRPC_STATUS, 2);
    private static final HttpData DATA =
            HttpData.wrap(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf()));

    private AtomicReference<Status> statusRef;
    private HttpDeframer<DeframedMessage> deframer;

    @BeforeEach
    void setUp() {
        statusRef = new AtomicReference<>();
        final TransportStatusListener statusListener = (status, metadata) -> statusRef.set(status);
        final HttpStreamDeframerHandler handler =
                new HttpStreamDeframerHandler(DecompressorRegistry.getDefaultInstance(), statusListener,
                                              Integer.MAX_VALUE);
        deframer = new HttpDeframer<>(handler, ByteBufAllocator.DEFAULT);
        handler.setDeframer(deframer);
    }

    @Test
    void onHeaders() {
        final StreamMessage<HttpObject> source = StreamMessage.of(HEADERS);
        source.subscribe(deframer);
        StepVerifier.create(deframer)
                    .thenRequest(1)
                    .expectNextCount(0)
                    .verifyComplete();
    }

    @Test
    void onTrailers() {
        final StreamMessage<HttpObject> source = StreamMessage.of(HEADERS, TRAILERS);
        source.subscribe(deframer);
        StepVerifier.create(deframer)
                    .thenRequest(1)
                    .expectNextCount(0)
                    .verifyComplete();
    }

    @Test
    void onMessage() throws Exception {
        final DeframedMessage deframedMessage = new DeframedMessage(GrpcTestUtil.requestByteBuf(), 0);
        final StreamMessage<HttpObject> source = StreamMessage.of(DATA);
        source.subscribe(deframer);
        StepVerifier.create(deframer)
                    .thenRequest(1)
                    .expectNextMatches(message -> {
                        final boolean result = message.equals(deframedMessage);
                        message.buf().release();
                        deframedMessage.buf().release();
                        return result;
                    })
                    .verifyComplete();
    }

    @Test
    void onMessage_deframeError() throws Exception {
        final StreamMessage<HttpData> malformed = StreamMessage.of(HttpData.ofUtf8("foobar"));
        malformed.subscribe(deframer);

        StepVerifier.create(deframer)
                    .thenRequest(1)
                    .verifyError(ArmeriaStatusException.class);
        await().untilAsserted(() -> {
            assertThat(statusRef.get().getCode()).isEqualTo(Status.INTERNAL.getCode());
        });
    }

    @Test
    void httpNotOk() {
        final StreamMessage<ResponseHeaders> source =
                StreamMessage.of(ResponseHeaders.of(HttpStatus.UNAUTHORIZED));
        source.subscribe(deframer);
        StepVerifier.create(deframer)
                    .thenRequest(1)
                    .verifyComplete();

        await().untilAsserted(() -> {
            assertThat(statusRef.get().getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());
        });
    }
}
