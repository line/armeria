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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.grpc.BindableService;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class UnframedGrpcServiceTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    private static class TestService extends TestServiceImplBase {

        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
        }
    }

    private static final TestService testService = new TestService();
    private static final int MAX_MESSAGE_BYTES = 1024;

    private static ServiceRequestContext ctx;
    private static HttpRequest request;

    @BeforeEach
    void setUp() {
        request = HttpRequest.of(HttpMethod.POST,
                                 "/armeria.grpc.testing.TestService/EmptyCall",
                                 MediaType.JSON_UTF_8, "{}");
        ctx = ServiceRequestContext.builder(request).eventLoop(eventLoop.get()).build();
    }

    @Test
    void statusOk() throws Exception {
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(testService);
        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        final AggregatedHttpResponse res = response.aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("{}");
    }

    @Test
    void statusCancelled() throws Exception {
        final TestService spyTestService = spy(testService);
        doThrow(Status.CANCELLED.withDescription("grpc error message").asRuntimeException())
                .when(spyTestService)
                .emptyCall(any(), any());
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(spyTestService);
        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.CLIENT_CLOSED_REQUEST);
        assertThat(res.contentUtf8())
                .isEqualTo("grpc-code: CANCELLED, grpc error message");
    }

    @Test
    void noContent() throws Exception {
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(new TestServiceImplBase() {
            @Override
            public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
                // Note that 'responseObserver.onNext()' is not called.
                responseObserver.onCompleted();
            }
        });
        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.contentUtf8())
                .isEqualTo("grpc-code: CANCELLED, Completed without a response");
    }

    @Test
    void shouldClosePooledObjectsForNonOK() {
        final CompletableFuture<HttpResponse> res = new CompletableFuture<>();
        final ByteBuf byteBuf = Unpooled.buffer();
        final ResponseHeaders responseHeaders = ResponseHeaders.builder(HttpStatus.OK)
                                                               .add(GrpcHeaderNames.GRPC_STATUS, "1")
                                                               .build();
        final AggregatedHttpResponse framedResponse = AggregatedHttpResponse.of(responseHeaders,
                                                                                HttpData.wrap(byteBuf));
        UnframedGrpcService.deframeAndRespond(ctx, framedResponse, res, UnframedGrpcErrorHandler.of());
        assertThat(byteBuf.refCnt()).isZero();
    }

    @Test
    void unframedGrpcStatusFunction() throws Exception {
        final TestService spyTestService = spy(testService);
        doThrow(Status.UNKNOWN.withDescription("grpc error message").asRuntimeException())
                .when(spyTestService)
                .emptyCall(any(), any());
        final UnframedGrpcStatusMappingFunction statusFunction = (ctx, status, cause) -> {
            if (status.getCode() == Code.UNKNOWN) {
                // not INTERNAL_SERVER_ERROR
                return HttpStatus.UNKNOWN;
            }
            return null;
        };
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(
                spyTestService,
                UnframedGrpcErrorHandler.ofPlainText(statusFunction));
        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.UNKNOWN);
        assertThat(res.contentUtf8())
                .isEqualTo("grpc-code: UNKNOWN, grpc error message");
    }

    @Test
    void unframedGrpcStatusFunction_default() throws Exception {
        final TestService spyTestService = spy(testService);
        doThrow(Status.UNKNOWN.withDescription("grpc error message").asRuntimeException())
                .when(spyTestService)
                .emptyCall(any(), any());
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(
                spyTestService,
                UnframedGrpcErrorHandler.ofPlainText());
        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.contentUtf8())
                .isEqualTo("grpc-code: UNKNOWN, grpc error message");
    }

    @Test
    void unframedGrpcStatusFunction_orElse() throws Exception {
        final TestService spyTestService = spy(testService);
        doThrow(Status.UNKNOWN.withDescription("grpc error message").asRuntimeException())
                .when(spyTestService)
                .emptyCall(any(), any());
        final UnframedGrpcStatusMappingFunction statusFunction = (ctx, status, cause) -> null; // return null
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(
                spyTestService,
                UnframedGrpcErrorHandler.ofPlainText(
                        statusFunction.orElse(UnframedGrpcStatusMappingFunction.of())));
        final HttpResponse response = unframedGrpcService.serve(ctx, request);
        final AggregatedHttpResponse res = response.aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.contentUtf8())
                .isEqualTo("grpc-code: UNKNOWN, grpc error message");
    }

    private static UnframedGrpcService buildUnframedGrpcService(BindableService bindableService) {
        return buildUnframedGrpcService(bindableService, UnframedGrpcErrorHandler.ofPlainText());
    }

    private static UnframedGrpcService buildUnframedGrpcService(BindableService bindableService,
                                                                UnframedGrpcErrorHandler errorHandler) {
        return (UnframedGrpcService) GrpcService.builder()
                                                .addService(bindableService)
                                                .maxRequestMessageLength(MAX_MESSAGE_BYTES)
                                                .maxResponseMessageLength(MAX_MESSAGE_BYTES)
                                                .enableUnframedRequests(true)
                                                .unframedGrpcErrorHandler(errorHandler)
                                                .build()
                                                .unwrap();
    }

    @Test
    void shouldThrowExceptionIfUnframedRequestHandlerAddedButUnframedRequestsAreDisabled() {
        final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                GrpcService.builder()
                           .maxRequestMessageLength(MAX_MESSAGE_BYTES)
                           .maxResponseMessageLength(MAX_MESSAGE_BYTES)
                           .enableUnframedRequests(false)
                           .unframedGrpcErrorHandler(UnframedGrpcErrorHandler.of())
                           .build());
        assertThat(exception).hasMessage(
                "'unframedGrpcErrorHandler' can only be set if unframed requests are enabled");
    }
}
