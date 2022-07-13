package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.grpc.testing.HttpJsonTranscodingTestServiceGrpc;
import com.linecorp.armeria.grpc.testing.Transcoding;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.DecoratorFactory;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.auth.AuthService;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public class GrpcHttpJsonTranscodingServiceAnnotatedAuthServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final GrpcService grpcService = GrpcService.builder().addService(
                            new AuthenticatedHttpJsonTranscodingTestService())
                    .enableHttpJsonTranscoding(true)
                    .build();
            sb.requestTimeoutMillis(5000);
            sb.decorator(LoggingService.newDecorator());
            sb.service(grpcService);
        }
    };

    private static final String TEST_CREDENTIAL_KEY = "credential";

    private final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final String gRpcUri = server.httpUri(GrpcSerializationFormats.PROTO).toString();

    private final BlockingWebClient webClient = server.webClient().blocking();

    private final HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceBlockingStub gRpcClient =
            GrpcClients.newClient(
                    gRpcUri, HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceBlockingStub.class);

    @Test
    void testAuthenticatedRpcMethod() throws Exception {
        Transcoding.GetMessageRequestV1 requestMessage = Transcoding.GetMessageRequestV1.newBuilder().setName("messages/1").build();
        Throwable exception = assertThrows(Throwable.class, () -> gRpcClient.getMessageV1(requestMessage));
        assertThat(exception instanceof StatusRuntimeException).isTrue();
        assertThat(((StatusRuntimeException) exception).getStatus().getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());

        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of(TEST_CREDENTIAL_KEY, Metadata.ASCII_STRING_MARSHALLER), "some-credential-string");
        Transcoding.Message result = gRpcClient.withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(metadata)
        ).getMessageV1(requestMessage);
        assertThat(result.getText()).isEqualTo("messages/1");
    }

    @Test
    void testAuthenticatedHttpJsonTranscoding() throws Exception {
        final AggregatedHttpResponse failResponse = webClient.get("/v1/messages/1");
        assertThat(failResponse.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        final AggregatedHttpResponse successResponse = webClient
                .execute(RequestHeaders.of(HttpMethod.GET, "/v1/messages/1",
                        TEST_CREDENTIAL_KEY, "some-credential-string"));
        final JsonNode root = mapper.readTree(successResponse.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    private static class AuthenticatedHttpJsonTranscodingTestService extends HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceImplBase {
        @Override
        @Authenticate
        public void getMessageV1(Transcoding.GetMessageRequestV1 request, StreamObserver<Transcoding.Message> responseObserver) {
            responseObserver.onNext(Transcoding.Message.newBuilder().setText(request.getName()).build());
            responseObserver.onCompleted();
        }
    }

    @DecoratorFactory(AuthServiceDecoratorFactoryFunction.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    private @interface Authenticate{}

    private static class AuthServiceDecoratorFactoryFunction implements DecoratorFactoryFunction<Authenticate> {
        @Override
        public Function<? super HttpService, ? extends HttpService> newDecorator(Authenticate parameter) {
            return AuthService.newDecorator(
                    new TestAuthorizer()
            );
        }
    }

    private static class TestAuthorizer implements Authorizer<HttpRequest> {
        @Override
        public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest data) {
            return UnmodifiableFuture.completedFuture(
                    data.headers().contains(TEST_CREDENTIAL_KEY)
            );
        }
    }
}
