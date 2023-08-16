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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class CustomRouteTest {

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
                                  .addService("foo", testService)
                                  .addService("bar", testService)
                                  .addService("/quz", testService)
                                  .addService("/qux/", testService)
                                  .addService("/v1/tests/empty", testService,
                                              TestServiceGrpc.getEmptyCallMethod())
                                  .addService("/v1/tests/empty/", testService,
                                              TestServiceGrpc.getEmptyCallMethod())
                                  .addService("/v1/tests/unary", testService,
                                              TestServiceGrpc.getUnaryCallMethod())
                                  .addService("/v1/tests/unary/", testService,
                                              TestServiceGrpc.getUnaryCallMethod())
                                  .enableUnframedRequests(true)
                                  .build());
        }
    };

    @CsvSource({ "/foo/EmptyCall", "/quz/EmptyCall", "/qux/EmptyCall", "/v1/tests/empty", "/v1/tests/empty/" })
    @ParameterizedTest
    void unframedJsonRequestWithCustomPath(String path) throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        final HttpRequest request =
                HttpRequest.of(HttpMethod.POST, path, MediaType.JSON_UTF_8, "{}");
        final AggregatedHttpResponse response = client.execute(request).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().getInt(GrpcHeaderNames.GRPC_STATUS)).isEqualTo(0);
    }

    @CsvSource({ "/bar/UnaryCall", "/v1/tests/unary", "/v1/tests/unary/" })
    @ParameterizedTest
    void unframedProtobufRequestWithCustomPath(String path) throws InvalidProtocolBufferException {
        final WebClient client = WebClient.of(server.httpUri());
        final SimpleRequest simpleRequest = SimpleRequest.newBuilder().setFillUsername(true).build();
        final HttpRequest request =
                HttpRequest.of(HttpMethod.POST, path, MediaType.PROTOBUF, simpleRequest.toByteArray());
        final AggregatedHttpResponse response = client.execute(request).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().getInt(GrpcHeaderNames.GRPC_STATUS)).isEqualTo(0);
        final SimpleResponse simpleResponse = SimpleResponse.parseFrom(response.content().array());
        assertThat(simpleResponse.getUsername()).isEqualTo("Armeria");
    }
}
