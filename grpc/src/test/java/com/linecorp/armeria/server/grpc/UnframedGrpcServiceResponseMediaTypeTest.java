package com.linecorp.armeria.server.grpc;

import com.linecorp.armeria.common.*;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.protobuf.EmptyProtos;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import io.grpc.BindableService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

public class UnframedGrpcServiceResponseMediaTypeTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    private static class TestService extends TestServiceGrpc.TestServiceImplBase {

        @Override
        public void emptyCall(EmptyProtos.Empty request, StreamObserver<EmptyProtos.Empty> responseObserver) {
            responseObserver.onNext(EmptyProtos.Empty.newBuilder().build());
            responseObserver.onCompleted();
        }
    }

    private static final TestService testService = new TestService();
    private static final int MAX_MESSAGE_BYTES = 1024;

    @Test
    void respondWithCorrespondingJSONMediaType() throws Exception {
        UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(testService);

        final HttpRequest request = HttpRequest.of(HttpMethod.POST,
                "/armeria.grpc.testing.TestService/EmptyCall",
                MediaType.JSON_UTF_8, "{}");
        ServiceRequestContext ctx = ServiceRequestContext.builder(request).eventLoop(eventLoop.get()).build();

        AggregatedHttpResponse res = unframedGrpcService.serve(ctx, request).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isNotNull();
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
    }

    @Test
    void respondWithCorrespondingProtobufMediaType() throws Exception {
        UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(testService);

        final HttpRequest request = HttpRequest.of(HttpMethod.POST,
                "/armeria.grpc.testing.TestService/EmptyCall",
                MediaType.PROTOBUF, EmptyProtos.Empty.getDefaultInstance().toByteArray());
        ServiceRequestContext ctx = ServiceRequestContext.builder(request).eventLoop(eventLoop.get()).build();

        AggregatedHttpResponse res = unframedGrpcService.serve(ctx, request).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isNotNull();
        assertThat(res.contentType()).isEqualTo(MediaType.PROTOBUF);
    }

    @Test
    void respondWithCorrespondingXProtobufMediaType() throws Exception {
        UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(testService);

        final HttpRequest request = HttpRequest.of(HttpMethod.POST,
                "/armeria.grpc.testing.TestService/EmptyCall",
                MediaType.X_PROTOBUF, EmptyProtos.Empty.getDefaultInstance().toByteArray());
        ServiceRequestContext ctx = ServiceRequestContext.builder(request).eventLoop(eventLoop.get()).build();

        AggregatedHttpResponse res = unframedGrpcService.serve(ctx, request).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isNotNull();
        assertThat(res.contentType()).isEqualTo(MediaType.X_PROTOBUF);
    }

    @Test
    void respondWithCorrespondingXGoogleProtobufMediaType() throws Exception {
        UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(testService);

        final HttpRequest request = HttpRequest.of(HttpMethod.POST,
                "/armeria.grpc.testing.TestService/EmptyCall",
                MediaType.X_GOOGLE_PROTOBUF, EmptyProtos.Empty.getDefaultInstance().toByteArray());
        ServiceRequestContext ctx = ServiceRequestContext.builder(request).eventLoop(eventLoop.get()).build();

        AggregatedHttpResponse res = unframedGrpcService.serve(ctx, request).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isNotNull();
        assertThat(res.contentType()).isEqualTo(MediaType.X_GOOGLE_PROTOBUF);
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
                .build();
    }
}
