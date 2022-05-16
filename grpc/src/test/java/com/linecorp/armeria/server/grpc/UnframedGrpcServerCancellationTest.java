/*
 * Copyright 2021 LINE Corporation
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;

class UnframedGrpcServerCancellationTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(2000);
            sb.service(GrpcService.builder()
                                  .addService(new TestService())
                                  .enableUnframedRequests(true)
                                  .build());
        }
    };

    @Test
    void serverTimeout() {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response =
                client.prepare()
                      .responseTimeoutMillis(0)
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute().aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.headers().getInt(GrpcHeaderNames.GRPC_STATUS)).isEqualTo(Code.CANCELLED.value());
    }

    private static class TestService extends TestServiceImplBase {

        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            // A call will be closed by a timeout scheduler.
        }
    }
}
