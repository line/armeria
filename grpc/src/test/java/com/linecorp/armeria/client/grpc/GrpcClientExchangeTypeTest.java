/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingInputCallRequest;
import testing.grpc.Messages.StreamingInputCallResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc.TestServiceStub;

class GrpcClientExchangeTypeTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                                  .build());
        }
    };

    private static TestServiceStub client;

    @BeforeAll
    static void beforeAll() {
        client = GrpcClients.newClient(server.httpUri(), TestServiceStub.class);
    }

    @Test
    void unary() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.unaryCall(SimpleRequest.getDefaultInstance(), new StreamObserver<SimpleResponse>() {
                @Override
                public void onNext(SimpleResponse value) {}

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            });
            assertThat(captor.get().exchangeType()).isEqualTo(ExchangeType.UNARY);
        }
    }

    @Test
    void requestStreaming() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final StreamObserver<StreamingInputCallRequest> requestStreamObserver =
                    client.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
                        @Override
                        public void onNext(StreamingInputCallResponse value) {}

                        @Override
                        public void onError(Throwable t) {}

                        @Override
                        public void onCompleted() {}
                    });
            requestStreamObserver.onNext(StreamingInputCallRequest.getDefaultInstance());
            requestStreamObserver.onCompleted();
            assertThat(captor.get().exchangeType()).isEqualTo(ExchangeType.REQUEST_STREAMING);
        }
    }

    @Test
    void responseStreaming() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance(),
                                       new StreamObserver<StreamingOutputCallResponse>() {
                                           @Override
                                           public void onNext(StreamingOutputCallResponse value) {}

                                           @Override
                                           public void onError(Throwable t) {}

                                           @Override
                                           public void onCompleted() {}
                                       }
            );
            assertThat(captor.get().exchangeType()).isEqualTo(ExchangeType.RESPONSE_STREAMING);
        }
    }

    @Test
    void bidiStreaming() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final StreamObserver<StreamingOutputCallRequest> requestStreamObserver =
                    client.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
                        @Override
                        public void onNext(StreamingOutputCallResponse value) {}

                        @Override
                        public void onError(Throwable t) {}

                        @Override
                        public void onCompleted() {}
                    });
            requestStreamObserver.onNext(StreamingOutputCallRequest.getDefaultInstance());
            requestStreamObserver.onCompleted();
            assertThat(captor.get().exchangeType()).isEqualTo(ExchangeType.BIDI_STREAMING);
        }
    }
}
