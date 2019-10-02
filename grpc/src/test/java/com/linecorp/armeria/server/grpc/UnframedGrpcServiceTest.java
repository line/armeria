/*
 * Copyright 2018 LINE Corporation
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;
import com.linecorp.armeria.testing.junit4.common.EventLoopRule;

import io.grpc.BindableService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class UnframedGrpcServiceTest {

    @ClassRule
    public static final EventLoopRule eventLoop = new EventLoopRule();

    private static class TestService extends TestServiceImplBase {

        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
        }
    }

    private static final TestService testService = new TestService();
    private static final int MAX_MESSAGE_BYTES = 1024;

    private ServiceRequestContext ctx;
    private HttpRequest request;

    @Before
    public void setUp() {
        request = HttpRequest.of(HttpMethod.POST,
                                 "/armeria.grpc.testing.TestService/EmptyCall",
                                 MediaType.JSON_UTF_8, "{}");
        ctx = ServiceRequestContextBuilder.of(request).eventLoop(eventLoop.get()).build();
    }

    @Test
    public void statusOk() throws Exception {
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(testService);
        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("{}");
    }

    @Test
    public void statusCancelled() throws Exception {
        final TestService spyTestService = spy(testService);
        doThrow(Status.CANCELLED.withDescription("grpc error message").asRuntimeException())
                .when(spyTestService)
                .emptyCall(any(), any());
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(spyTestService);
        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.CLIENT_CLOSED_REQUEST);
        assertThat(res.contentUtf8())
                .isEqualTo("http-status: 499, Client Closed Request\n" +
                           "Caused by: \n" +
                           "grpc-status: 1, CANCELLED, grpc error message");
    }

    @Test
    public void noContent() throws Exception {
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(new TestServiceImplBase() {
            @Override
            public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
                // Note that 'responseObserver.onNext()' is not called.
                responseObserver.onCompleted();
            }
        });
        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.content().isEmpty()).isTrue();
    }

    private static UnframedGrpcService buildUnframedGrpcService(BindableService bindableService) {
        return (UnframedGrpcService) GrpcService.builder()
                                                .addService(bindableService)
                                                .setMaxInboundMessageSizeBytes(MAX_MESSAGE_BYTES)
                                                .setMaxOutboundMessageSizeBytes(MAX_MESSAGE_BYTES)
                                                .supportedSerializationFormats(
                                                        GrpcSerializationFormats.values())
                                                .enableUnframedRequests(true)
                                                .build();
    }
}
