package com.linecorp.armeria.server.grpc.validation;

import com.google.protobuf.MessageLiteOrBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.grpc.testing.Hello;
import com.linecorp.armeria.grpc.testing.HelloServiceGrpc.HelloServiceBlockingStub;
import com.linecorp.armeria.internal.common.grpc.HeloServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RequestValidationInterceptorTest {

    static String ERROR_MESSAGE = "invalid argument";

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            List<RequestValidator<MessageLiteOrBuilder>> validators = new ArrayList<>();

            validators.add((RequestValidator) new HelloRequestValidator());

            RequestValidatorResolver requestValidatorResolver = new RequestValidatorResolver(validators);
            sb.service(GrpcService.builder()
                    .addService(new HeloServiceImpl())
                    .intercept(new RequestValidationInterceptor(requestValidatorResolver))
                    .build());
        }
    };

    @Test
    void validation_fail_test() {
        HelloServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                .build(HelloServiceBlockingStub.class);

        final Throwable cause = catchThrowable(() -> client.hello(Hello.HelloRequest.getDefaultInstance()));
        assertThat(cause).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) cause).getStatus().getCode()).isEqualTo(Status.INVALID_ARGUMENT.getCode());
    }

    @Test
    void validation_success_test() {
        HelloServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                .build(HelloServiceBlockingStub.class);

        Hello.HelloResponse response = client.hello(
                Hello.HelloRequest.newBuilder()
                        .setMessage("success")
                        .build()
        );

        assertThat(response.getMessage()).isEqualTo("success");
    }

    private static class HelloRequestValidator implements RequestValidator<Hello.HelloRequest> {

        @Override
        public ValidationResult isValid(Hello.HelloRequest request) {
            if (request.getMessage().equals("success")) {
                return new ValidationResult(true, null);
            }

            return new ValidationResult(false, ERROR_MESSAGE);
        }
    }

}
