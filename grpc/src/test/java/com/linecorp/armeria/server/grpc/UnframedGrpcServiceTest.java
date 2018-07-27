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
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.common.EventLoopRule;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBufAllocator;

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

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private ServiceRequestContext ctx;

    @Mock
    private HttpRequest request;

    private UnframedGrpcService unframedGrpcService;

    @Before
    public void setUp() {
        when(ctx.mappedPath()).thenReturn("/armeria.grpc.testing.TestService/EmptyCall");
        when(ctx.eventLoop()).thenReturn(eventLoop.get());
        when(ctx.contextAwareEventLoop()).thenReturn(eventLoop.get());
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        final DefaultRequestLog log = new DefaultRequestLog(ctx);
        when(ctx.log()).thenReturn(log);
        when(ctx.logBuilder()).thenReturn(log);

        when(request.headers())
                .thenReturn(HttpHeaders.of(HttpMethod.POST, "/armeria.grpc.testing.TestService/EmptyCall")
                                       .contentType(MediaType.JSON_UTF_8));
        when(request.aggregateWithPooledObjects(any(), any()))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                AggregatedHttpMessage.of(HttpMethod.POST,
                                                         "/armeria.grpc.testing.TestService/EmptyCall",
                                                         MediaType.JSON_UTF_8, "{}")));
    }

    @Test
    public void statusOk() throws Exception {
        unframedGrpcService =
                (UnframedGrpcService) new GrpcServiceBuilder().addService(testService)
                                                              .setMaxInboundMessageSizeBytes(MAX_MESSAGE_BYTES)
                                                              .setMaxOutboundMessageSizeBytes(MAX_MESSAGE_BYTES)
                                                              .supportedSerializationFormats(
                                                                      GrpcSerializationFormats.values())
                                                              .enableUnframedRequests(true)
                                                              .build();

        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        AggregatedHttpMessage aggregatedHttpMessage = response.aggregate().get();
        assertThat(aggregatedHttpMessage.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(aggregatedHttpMessage.content().toStringUtf8()).isEqualTo("{}");
    }

    @Test
    public void statusCancelled() throws Exception {
        TestService spyTestService = spy(testService);
        doThrow(Status.CANCELLED.withDescription("grpc error message").asRuntimeException())
                .when(spyTestService)
                .emptyCall(any(), any());
        unframedGrpcService =
                (UnframedGrpcService) new GrpcServiceBuilder().addService(spyTestService)
                                                              .setMaxInboundMessageSizeBytes(MAX_MESSAGE_BYTES)
                                                              .setMaxOutboundMessageSizeBytes(MAX_MESSAGE_BYTES)
                                                              .supportedSerializationFormats(
                                                                      GrpcSerializationFormats.values())
                                                              .enableUnframedRequests(true)
                                                              .build();
        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        AggregatedHttpMessage aggregatedHttpMessage = response.aggregate().get();
        assertThat(aggregatedHttpMessage.headers().status()).isEqualTo(HttpStatus.CLIENT_CLOSED_REQUEST);
        assertThat(aggregatedHttpMessage.content().toStringUtf8())
                .isEqualTo("http-status: 499, Client Closed Request\n" +
                           "Caused by: \n" +
                           "grpc-status: 1, CANCELLED, grpc error message");
    }
}
