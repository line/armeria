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

package com.linecorp.armeria.it.grpc;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.PayloadType;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleRequest.NestedRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcLoggingServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(5000);
            sb.service(GrpcService.builder()
                                  .addService(new FirstTestServiceImpl())
                                  .build());
        }
    };

    @Test
    void methodDecorators() throws Exception {
        final Logger logger = mock(Logger.class);
        doReturn(true).when(logger).isInfoEnabled();
        doNothing().when(logger).info(anyString());

        server.server().reconfigure(sb -> {
            sb.decorator(
                    LoggingService.builder()
                                  .samplingRate(1)
                                  .logWriter(
                                          LogWriter.builder()
                                                   .logger(logger)
                                                   .logFormatter(LogFormatter.ofJson())
                                                   .requestLogLevel(LogLevel.INFO)
                                                   .successfulResponseLogLevel(LogLevel.INFO)
                                                   .build()
                                  )
                                  .newDecorator()
            );
            sb.service(GrpcService.builder()
                                  .addService(new FirstTestServiceImpl())
                                  .build());
        });

        final TestServiceBlockingStub client = GrpcClients.newClient(server.httpUri(),
                                                                     TestServiceBlockingStub.class);

        client.unaryCall(SimpleRequest.newBuilder()
                                      .setPayload(
                                              Payload.newBuilder()
                                                     .setBody(ByteString.copyFromUtf8("Hello World!"))
                                                     .setType(PayloadType.COMPRESSABLE)
                                                     .build()
                                      )
                                      .setFillUsername(true)
                                      .setNestedRequest(
                                              NestedRequest.newBuilder()
                                                           .setNestedPayload("zanzibar")
                                                           .build()
                                      )
                                      .build());

        // Verify that the logger was called with the expected arguments.
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger).isInfoEnabled();
        verify(logger).info(messageCaptor.capture());

        final ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node = objectMapper.readTree(messageCaptor.getValue());

        assertThatJson(node).node("request.payload.body").isEqualTo("Hello World!");
    }

    private static class FirstTestServiceImpl extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }
}
