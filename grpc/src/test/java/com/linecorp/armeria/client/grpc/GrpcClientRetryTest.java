package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;

final class GrpcClientRetryTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .build());
        }
    };

    @Test
    void childrenContextsHaveSameRpcRequest() {
        final TestServiceBlockingStub client =
                Clients.builder(server.uri(SessionProtocol.HTTP, GrpcSerializationFormats.PROTO))
                       .decorator(RetryingClient.newDecorator(retryRuleWithContent()))
                       .build(TestServiceBlockingStub.class);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final SimpleResponse result = client.unaryCall(SimpleRequest.getDefaultInstance());
            assertThat(result.getUsername()).isEqualTo("my name");
            final ClientRequestContext context = captor.get();
            final List<RequestLogAccess> children = context.log().children();
            assertThat(children).hasSize(3);
            children.forEach(child -> {
                assertThat(context.rpcRequest()).isSameAs(child.context().rpcRequest());
            });
        }
    }

    private static RetryRuleWithContent<HttpResponse> retryRuleWithContent() {
        return RetryRuleWithContent.<HttpResponse>builder()
                .onResponseHeaders((ctx, headers) -> {
                    // Trailers may be sent together with response headers, with no message in the body.
                    final Integer grpcStatus = headers.getInt(GrpcHeaderNames.GRPC_STATUS);
                    return grpcStatus != null && grpcStatus != 0;
                })
                .onResponse((ctx, res) -> res.aggregate().thenApply(aggregatedRes -> {
                    final HttpHeaders trailers = aggregatedRes.trailers();
                    return trailers.getInt(GrpcHeaderNames.GRPC_STATUS, -1) != 0;
                }))
                .thenBackoff();
    }

    private static class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {

        private final AtomicInteger retryCounter = new AtomicInteger();

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            switch (retryCounter.getAndIncrement()) {
                case 0:
                    responseObserver.onError(new StatusException(Status.INTERNAL));
                    break;
                case 1:
                    responseObserver.onNext(SimpleResponse.newBuilder().setUsername("my name").build());
                    responseObserver.onError(new StatusException(Status.INTERNAL));
                    break;
                default:
                    responseObserver.onNext(SimpleResponse.newBuilder().setUsername("my name").build());
                    responseObserver.onCompleted();
                    break;
            }
        }
    }
}
