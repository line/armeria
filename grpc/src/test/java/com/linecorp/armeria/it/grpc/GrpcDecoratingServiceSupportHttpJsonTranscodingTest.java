package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.grpc.testing.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceImplBase;
import com.linecorp.armeria.grpc.testing.Transcoding.GetMessageRequestV1;
import com.linecorp.armeria.grpc.testing.Transcoding.Message;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;

public class GrpcDecoratingServiceSupportHttpJsonTranscodingTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final GrpcService grpcService = GrpcService.builder().addService(
                    new HttpJsonTranscodingTestService()).enableHttpJsonTranscoding(true).build();
            sb.requestTimeoutMillis(5000);
            sb.decorator(LoggingService.newDecorator());
            sb.service(grpcService);
        }
    };

    private static String FIRST_TEST_RESULT = "";

    private final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final WebClient webClient = WebClient.builder(server.httpUri()).build();

    @Test
    void shouldGetMessageV1ByWebClient() throws Exception {
        final AggregatedHttpResponse response = webClient.get("/v1/messages/1").aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
        assertThat(FIRST_TEST_RESULT).isEqualTo("FirstDecorator/MethodFirstDecorator");
    }

    @Decorator(FirstDecorator.class)
    private static class HttpJsonTranscodingTestService extends HttpJsonTranscodingTestServiceImplBase {

        @Override
        @Decorator(MethodFirstDecorator.class)
        public void getMessageV1(GetMessageRequestV1 request, StreamObserver<Message> responseObserver) {
            responseObserver.onNext(Message.newBuilder().setText(request.getName()).build());
            responseObserver.onCompleted();
        }
    }

    private static class FirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            FIRST_TEST_RESULT += "FirstDecorator/";
            return delegate.serve(ctx, req);
        }
    }

    private static class MethodFirstDecorator implements DecoratingHttpServiceFunction {
        @Override
        public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            FIRST_TEST_RESULT += "MethodFirstDecorator";
            return delegate.serve(ctx, req);
        }
    }
}
