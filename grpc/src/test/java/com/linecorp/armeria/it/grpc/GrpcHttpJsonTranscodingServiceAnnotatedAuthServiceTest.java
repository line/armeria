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

package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
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
import testing.grpc.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceBlockingStub;
import testing.grpc.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceImplBase;
import testing.grpc.Transcoding;

public class GrpcHttpJsonTranscodingServiceAnnotatedAuthServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final GrpcService grpcService = GrpcService.builder()
                    .addService(new AuthenticatedHttpJsonTranscodingTestService())
                    .enableHttpJsonTranscoding(true)
                    .build();
            sb.requestTimeoutMillis(5000);
            sb.decorator(LoggingService.newDecorator());
            sb.service(grpcService);
        }
    };

    private static final String TEST_CREDENTIAL_KEY = "credential";

    private final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final BlockingWebClient webClient = server.webClient().blocking();

    private final HttpJsonTranscodingTestServiceBlockingStub grpcClient =
            GrpcClients.newClient(server.httpUri(), HttpJsonTranscodingTestServiceBlockingStub.class);

    @Test
    void testAuthenticatedRpcMethod() throws Exception {
        final Transcoding.GetMessageRequestV1 requestMessage = Transcoding.GetMessageRequestV1.newBuilder()
                .setName("messages/1").build();
        final Throwable exception = assertThrows(Throwable.class,
                () -> grpcClient.getMessageV1(requestMessage).getText());
        assertThat(exception).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) exception).getStatus().getCode())
                .isEqualTo(Status.UNAUTHENTICATED.getCode());

        final Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of(TEST_CREDENTIAL_KEY, Metadata.ASCII_STRING_MARSHALLER),
                "some-credential-string");
        final Transcoding.Message result =
                grpcClient.withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(metadata)
        ).getMessageV1(requestMessage);
        assertThat(result.getText()).isEqualTo("messages/1");
    }

    @Test
    void testAuthenticatedHttpJsonTranscoding() throws Exception {
        final AggregatedHttpResponse failResponse = webClient.get("/v1/messages/1");
        assertThat(failResponse.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        final JsonNode root = webClient.prepare()
                .get("/v1/messages/1")
                .header(TEST_CREDENTIAL_KEY, "some-credential-string")
                .asJson(JsonNode.class)
                .execute()
                .content();
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    private static class AuthenticatedHttpJsonTranscodingTestService
            extends HttpJsonTranscodingTestServiceImplBase {
        @Override
        @Authenticate
        public void getMessageV1(Transcoding.GetMessageRequestV1 request,
                                 StreamObserver<Transcoding.Message> responseObserver) {
            responseObserver.onNext(Transcoding.Message.newBuilder().setText(request.getName()).build());
            responseObserver.onCompleted();
        }
    }

    @DecoratorFactory(AuthServiceDecoratorFactoryFunction.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    private @interface Authenticate {}

    private static class AuthServiceDecoratorFactoryFunction implements DecoratorFactoryFunction<Authenticate> {
        @Override
        public Function<? super HttpService, ? extends HttpService>
        newDecorator(Authenticate parameter) {
            return AuthService.newDecorator(new TestAuthorizer());
        }
    }

    private static class TestAuthorizer implements Authorizer<HttpRequest> {
        @Override
        public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req) {
            return UnmodifiableFuture.completedFuture(
                    req.headers().contains(TEST_CREDENTIAL_KEY)
            );
        }
    }
}
