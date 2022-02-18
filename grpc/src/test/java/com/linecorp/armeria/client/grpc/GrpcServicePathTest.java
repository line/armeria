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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class GrpcServicePathTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final GrpcService grpcService = GrpcService.builder()
                                                       .addService(new TestServiceImpl(
                                                               Executors.newSingleThreadScheduledExecutor()))
                                                       .build();
            sb.serviceUnder("/grpc", grpcService);
            sb.service(grpcService);
        }
    };

    @CsvSource({ "/grpc", "/grpc/" })
    @ParameterizedTest
    void prefix(String path) {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .pathPrefix(path)
                                                          .build(TestServiceBlockingStub.class);
        final SimpleResponse response = client.unaryCall(SimpleRequest.newBuilder()
                                                                      .setResponseSize(10)
                                                                      .build());
        assertThat(response.getPayload().getBody().size()).isEqualTo(10);
    }
}
