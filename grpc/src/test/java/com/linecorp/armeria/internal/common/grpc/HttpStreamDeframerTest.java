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
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.DecompressorRegistry;
import io.grpc.Status;
import reactor.test.StepVerifier;

class HttpStreamDeframerTest {

    private static final ResponseHeaders HEADERS = ResponseHeaders.of(HttpStatus.OK);
    private static final HttpHeaders TRAILERS = HttpHeaders.of(GrpcHeaderNames.GRPC_STATUS, 2);
    private static final HttpData DATA =
            HttpData.wrap(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf()));

    private HttpStreamDeframer deframer;
    private AtomicReference<Status> statusRef;

    @BeforeEach
    void setUp() {
        statusRef = new AtomicReference<>();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final TransportStatusListener statusListener = (status, metadata) -> statusRef.set(status);
        deframer = new HttpStreamDeframer(DecompressorRegistry.getDefaultInstance(), ctx, statusListener,
                                          new UnwrappingGrpcExceptionHandleFunction(
                                                  GrpcExceptionHandlerFunction.of()), Integer.MAX_VALUE,
                                          false, true);
    }

    @Test
    void onHeaders() {
        final HttpResponse source = HttpResponse.of(HEADERS);
        final StreamMessage<DeframedMessage> deframed = source.decode(deframer);
        deframer.setDeframedStreamMessage(deframed);
        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .expectNextCount(0)
                    .verifyComplete();
    }

    @Test
    void onTrailers() {
        final HttpResponse source = HttpResponse.of(HEADERS, TRAILERS);
        final StreamMessage<DeframedMessage> deframed = source.decode(deframer);
        deframer.setDeframedStreamMessage(deframed);
        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .expectNextCount(0)
                    .verifyComplete();
    }

    @Test
    void onMessage() throws Exception {
        final DeframedMessage deframedMessage = new DeframedMessage(GrpcTestUtil.requestByteBuf(), 0);
        final HttpResponse source = HttpResponse.of(HEADERS, DATA);
        final StreamMessage<DeframedMessage> deframed = source.decode(deframer);
        deframer.setDeframedStreamMessage(deframed);
        StepVerifier.create(deframed)
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
        final HttpResponse malformed = HttpResponse.of(HEADERS, HttpData.ofUtf8("foobar"));
        final StreamMessage<DeframedMessage> deframed = malformed.decode(deframer);
        deframer.setDeframedStreamMessage(deframed);

        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .verifyError(ArmeriaStatusException.class);
        await().untilAsserted(() -> {
            assertThat(statusRef.get().getCode()).isEqualTo(Status.INTERNAL.getCode());
        });
    }

    @Test
    void httpNotOk() {
        final HttpResponse source = HttpResponse.of(ResponseHeaders.of(HttpStatus.UNAUTHORIZED));
        final StreamMessage<DeframedMessage> deframed = source.decode(deframer);
        deframer.setDeframedStreamMessage(deframed);
        StepVerifier.create(deframed)
                    .thenRequest(1)
                    .verifyComplete();

        await().untilAsserted(() -> {
            assertThat(statusRef.get().getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());
        });
    }
}
