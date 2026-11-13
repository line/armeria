/*
 * Copyright 2026 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcRelayedErrorHeadersTest {

    @RegisterExtension
    static final ServerExtension downstream = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder().addService(new FailingService()).build());
        }
    };

    @RegisterExtension
    static final ServerExtension relay = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new RelayingService())
                                  .useBlockingTaskExecutor(true)
                                  .build());
        }
    };

    @Test
    void relayedTrailersOnlyErrorHasSingleTransportHeaders() {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST,
                                       '/' + TestServiceGrpc.getUnaryCallMethod().getFullMethodName())
                              .contentType(GrpcSerializationFormats.PROTO.mediaType())
                              .add(HttpHeaderNames.TE, "trailers")
                              .build();

        final AggregatedHttpResponse res = WebClient.of(relay.uri(SessionProtocol.H2C))
                                                    .blocking()
                                                    .execute(headers, HttpData.wrap(new byte[5]));

        assertThat(res.headers().get(GrpcHeaderNames.GRPC_STATUS)).isEqualTo("5");
        assertThat(res.headers().getAll(HttpHeaderNames.CONTENT_TYPE)).hasSize(1);
        assertThat(res.headers().getAll(GrpcHeaderNames.GRPC_ENCODING)).hasSize(1);
        assertThat(res.headers().getAll(GrpcHeaderNames.GRPC_ACCEPT_ENCODING)).hasSize(1);
    }

    private static final class FailingService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
        }
    }

    private static final class RelayingService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final TestServiceBlockingStub stub =
                    GrpcClients.newClient(downstream.httpUri(), TestServiceBlockingStub.class);
            try {
                responseObserver.onNext(stub.unaryCall(request));
                responseObserver.onCompleted();
            } catch (StatusRuntimeException e) {
                responseObserver.onError(e);
            }
        }
    }
}
