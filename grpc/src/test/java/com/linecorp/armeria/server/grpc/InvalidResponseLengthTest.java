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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.ResponseParameters;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceStub;

class InvalidResponseLengthTest {

    private static final Metadata.Key<String> CONTENT_LENGTH =
            Metadata.Key.of("content-length", Metadata.ASCII_STRING_MARSHALLER);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final ServerInterceptor interceptor = new ServerInterceptor() {
                @Override
                public <I, O> Listener<I> interceptCall(ServerCall<I, O> call,
                                                        Metadata requestHeaders,
                                                        ServerCallHandler<I, O> next) {
                    return next.startCall(new SimpleForwardingServerCall<I, O>(call) {
                        @Override
                        public void sendHeaders(Metadata responseHeaders) {
                            // Set a wrong content-length
                            responseHeaders.put(CONTENT_LENGTH, "1");
                            super.sendHeaders(responseHeaders);
                        }
                    }, requestHeaders);
                }
            };

            final GrpcService grpcService =
                    GrpcService.builder()
                               .addService(new TestServiceImpl(CommonPools.blockingTaskExecutor()))
                               .intercept(interceptor)
                               .build();

            sb.service(grpcService);
        }
    };

    @Test
    void streamingResponse() {
        final TestServiceStub client = GrpcClients.newClient(server.httpUri(), TestServiceStub.class);
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest
                        .newBuilder()
                        .addResponseParameters(ResponseParameters.newBuilder().setSize(10))
                        .addResponseParameters(ResponseParameters.newBuilder().setSize(11))
                        .addResponseParameters(ResponseParameters.newBuilder().setSize(12))
                        .addResponseParameters(ResponseParameters.newBuilder().setSize(13))
                        .build();
        final AtomicInteger responseCount = new AtomicInteger();
        final AtomicBoolean completed = new AtomicBoolean();

        client.streamingOutputCall(request, new StreamObserver<StreamingOutputCallResponse>() {

            @Override
            public void onNext(StreamingOutputCallResponse value) {
                responseCount.incrementAndGet();
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {
                completed.set(true);
            }
        });

        await().untilTrue(completed);
        assertThat(responseCount).hasValue(4);
    }

    @Test
    void unaryResponse() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        final SimpleResponse simpleResponse = client.unaryCall(SimpleRequest.newBuilder()
                                                                            .setResponseSize(100)
                                                                            .build());
        assertThat(simpleResponse.getPayload().getBody().size()).isEqualTo(100);
    }
}
