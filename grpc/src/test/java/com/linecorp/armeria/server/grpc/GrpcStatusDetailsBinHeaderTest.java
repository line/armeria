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

import static com.linecorp.armeria.server.grpc.JsonUnframedGrpcErrorHandler.decodeGrpcStatusDetailsBin;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import com.google.rpc.Status;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

public class GrpcStatusDetailsBinHeaderTest {

    @RegisterExtension
    static ServerExtension testServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, testServiceWithErrorDetails);
        }
    };

    private static class TestServiceWithErrorDetails extends TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            throw StatusProto.toStatusRuntimeException(googleRpcStatus);
        }
    }

    private static final TestServiceWithErrorDetails testServiceWithErrorDetails =
            new TestServiceWithErrorDetails();

    private static final Status googleRpcStatus =
            Status.newBuilder()
                  .setCode(2)
                  .setMessage("Unknown Exceptions Test")
                  .addDetails(Any.pack(ErrorInfo.newBuilder()
                                                .setDomain("test")
                                                .setReason("Unknown Exception").build()))
                  .build();

    private static void configureServer(ServerBuilder sb,
                                        TestServiceImplBase testService) {
        sb.service(GrpcService.builder()
                              .addService(testService)
                              .enableUnframedRequests(true)
                              .unframedGrpcErrorHandler((ctx, status, response) -> {
                                  final ResponseHeaders responseHeaders
                                          = ResponseHeaders.builder().addInt(HttpHeaderNames.STATUS, 500)
                                                           .set(GrpcHeaderNames.GRPC_STATUS_DETAILS_BIN,
                                                                response.headers().get(
                                                                        GrpcHeaderNames
                                                                                .GRPC_STATUS_DETAILS_BIN))
                                                           .build();
                                  return HttpResponse.ofJson(responseHeaders, ImmutableMap.builder());
                              })
                              .build());
    }

    @Test
    void googleRpcErrorDetail() throws InvalidProtocolBufferException {
        final BlockingWebClient client = testServer.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        final Status status = decodeGrpcStatusDetailsBin(
                response.headers().get(GrpcHeaderNames.GRPC_STATUS_DETAILS_BIN));
        assertThat(status).isEqualTo(googleRpcStatus);
        assertThat(response.trailers()).isEmpty();
    }
}
