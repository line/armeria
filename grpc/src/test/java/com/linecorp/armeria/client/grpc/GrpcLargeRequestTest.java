/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.FlowControlTestServiceGrpc.FlowControlTestServiceImplBase;
import testing.grpc.FlowControlTestServiceGrpc.FlowControlTestServiceStub;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;

class GrpcLargeRequestTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.maxRequestLength(10);
            sb.service(GrpcService.builder()
                                  .addService(new FlowControlTestServiceImplBase() {
                                      @Override
                                      public StreamObserver<SimpleRequest> noBackPressure(
                                              StreamObserver<SimpleResponse> responseObserver) {
                                          responseObserver.onNext(SimpleResponse.newBuilder()
                                                                                .setUsername("armeria")
                                                                                .build());
                                          return new StreamObserver<SimpleRequest>() {
                                              @Override
                                              public void onNext(SimpleRequest value) {}

                                              @Override
                                              public void onError(Throwable t) {}

                                              @Override
                                              public void onCompleted() {}
                                          };
                                      }
                                  })
                                  .build());
        }
    };

    @Test
    void testLargeRequest() throws Exception {
        final FlowControlTestServiceStub stub = GrpcClients.builder(server.httpUri())
                                                           .maxResponseLength(0)
                                                           .responseTimeoutMillis(0)
                                                           .build(FlowControlTestServiceStub.class);
        final CountDownLatch onNextLatch = new CountDownLatch(1);
        final CountDownLatch onErrorLatch = new CountDownLatch(1);
        final StreamObserver<SimpleRequest> observer = stub.noBackPressure(
                new StreamObserver<SimpleResponse>() {
                    @Override
                    public void onNext(SimpleResponse value) {
                        assertThat(value).isEqualTo(SimpleResponse.newBuilder()
                                                                  .setUsername("armeria")
                                                                  .build());
                        onNextLatch.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                        assertThat(t).isInstanceOf(StatusRuntimeException.class);
                        assertThat(((StatusRuntimeException) t).getStatus().getCause()).hasMessageContaining(
                                "ContentTooLargeException");
                        onErrorLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {}
                });
        final SimpleRequest req =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(Strings.repeat("a", 4)))
                                                .build())
                             .build();
        onNextLatch.await();
        // Send request after the responseHeaders is received.
        observer.onNext(req);
        observer.onNext(req);
        observer.onNext(req);
        onErrorLatch.await();
    }
}
