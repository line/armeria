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

package com.linecorp.armeria.internal.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;

class GrpcDocServiceWithCustomRouteTest {

    private static final class TestService extends TestServiceImplBase {

        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            if (request.getFillUsername()) {
                responseObserver.onNext(SimpleResponse.newBuilder().setUsername("Armeria").build());
            } else {
                responseObserver.onNext(SimpleResponse.getDefaultInstance());
            }
            responseObserver.onCompleted();
        }
    }

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final TestService testService = new TestService();
            sb.service(GrpcService.builder()
                                  // TODO(ikhoon) Need to fix tailing slush in DocService
                                  .addService("/empty", testService, TestServiceGrpc.getEmptyCallMethod())
                                  .addService("/unary", testService, TestServiceGrpc.getUnaryCallMethod())
                                  .enableUnframedRequests(true)
                                  .build());
            sb.serviceUnder("/docs",
                            DocService.builder()
                                      .exampleRequests(TestServiceGrpc.SERVICE_NAME, "UnaryCall",
                                                       SimpleRequest.newBuilder()
                                                                    .setResponseSize(1000)
                                                                    .setFillUsername(true).build())
                                      .build()
            );
        }
    };

    @Test
    void filteredSpecification() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpRequest request =
                HttpRequest.of(HttpMethod.POST, "/empty", MediaType.JSON_UTF_8, "{}");
        final AggregatedHttpResponse response = client.execute(request).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().getInt(GrpcHeaderNames.GRPC_STATUS)).isEqualTo(0);
    }
}
