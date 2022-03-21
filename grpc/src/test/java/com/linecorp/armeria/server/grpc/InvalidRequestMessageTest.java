/*
 * Copyright 2022 LINE Corporation
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

import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.grpc.protocol.UnaryGrpcClient;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.internal.common.grpc.protocol.UnaryGrpcSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

class InvalidRequestMessageTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                                  .maxRequestMessageLength(100)
                                  .build());
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @Test
    void invalidProto() throws InterruptedException {
        final UnaryGrpcClient client = Clients.builder(server.httpUri(UnaryGrpcSerializationFormats.PROTO))
                                              .build(UnaryGrpcClient.class);
        assertThatThrownBy(() -> {
            client.execute(TestServiceGrpc.getUnaryCallMethod().getFullMethodName(), "INVALID".getBytes())
                  .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ArmeriaStatusException.class)
          .hasMessageContaining("Invalid protobuf byte sequence");

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.requestCause())
                .isInstanceOf(StatusException.class)
                .hasMessageContaining("Invalid protobuf byte sequence");
    }

    @Test
    void invalidSize() throws InterruptedException {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        final Payload payload = Payload.newBuilder()
                                       .setBody(ByteString.copyFrom(Strings.repeat("A", 101).getBytes()))
                                       .build();
        assertThatThrownBy(() -> {
            client.unaryCall(SimpleRequest.newBuilder()
                                          .setPayload(payload)
                                          .build());
        }).isInstanceOf(StatusRuntimeException.class)
          .satisfies(ex -> {
              assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                      .isEqualTo(Code.RESOURCE_EXHAUSTED);
          })
          .hasMessageContaining("exceeds maximum: 100.");

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.requestCause())
                .isInstanceOf(StatusException.class)
                .hasMessageContaining("exceeds maximum: 100.");
    }
}
