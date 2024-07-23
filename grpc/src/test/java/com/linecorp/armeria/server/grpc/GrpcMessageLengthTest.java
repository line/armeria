/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.ResponseParameters;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class GrpcMessageLengthTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final GrpcService grpcService =
                    GrpcService.builder()
                               .addService(new TestServiceImpl(
                                       Executors.newSingleThreadScheduledExecutor()))
                               .maxRequestMessageLength(1000)
                               .maxResponseMessageLength(1000)
                               .exceptionHandler((ctx, status, cause, metadata) -> {
                                   if (cause instanceof StatusRuntimeException) {
                                       assertThat(((StatusRuntimeException) cause).getStatus().getCode())
                                               .isEqualTo(status.getCode());
                                   } else if (cause instanceof StatusException) {
                                       assertThat(((StatusException) cause).getStatus().getCode())
                                               .isEqualTo(status.getCode());
                                   }
                                   return status.withDescription(
                                           status.getDescription() + ": exception handled");
                               })
                               .build();
            sb.service(grpcService);
        }
    };

    @Test
    void shouldHandleExceedingRequestLength() {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .build(TestServiceBlockingStub.class);
        final Payload payload = Payload.newBuilder()
                                       .setBody(ByteString.copyFrom(Strings.repeat("a", 1001),
                                                                    StandardCharsets.UTF_8))
                                       .build();
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setPayload(payload)
                                                   .build();
        assertResourceExhausted(() -> client.unaryCall(request));
    }

    @Test
    void shouldHandleExceedingResponseLength() {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .build(TestServiceBlockingStub.class);
        final Payload payload = Payload.newBuilder()
                                       .build();
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setResponseSize(1001)
                                                   .setPayload(payload)
                                                   .build();
        assertResourceExhausted(() -> client.unaryCall(request));
    }

    @Test
    void shouldHandleExceedingRequestLength_streaming() {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .build(TestServiceBlockingStub.class);
        final Payload payload = Payload.newBuilder()
                                       .setBody(ByteString.copyFrom(Strings.repeat("a", 1001),
                                                                    StandardCharsets.UTF_8))
                                       .build();
        final StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
                                                                             .setPayload(payload)
                                                                             .build();
        assertResourceExhausted(() -> {
            final Iterator<StreamingOutputCallResponse> response = client.streamingOutputCall(request);
            // Drain responses
            while (response.hasNext()) {
                response.next();
            }
        });
    }

    @Test
    void shouldHandleExceedingResponseLength_streaming() {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .build(TestServiceBlockingStub.class);
        final ResponseParameters parameters = ResponseParameters.newBuilder()
                                                                .setSize(1001)
                                                                .build();
        final StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
                                                                             .addResponseParameters(parameters)
                                                                             .build();
        assertResourceExhausted(() -> {
            final Iterator<StreamingOutputCallResponse> response = client.streamingOutputCall(request);
            // Drain responses
            while (response.hasNext()) {
                response.next();
            }
        });
    }

    private static void assertResourceExhausted(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
                    assertThat(cause.getMessage()).endsWith(": exception handled");
                });
    }
}
