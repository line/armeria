package example.armeria.reverseproxy;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import com.linecorp.armeria.server.grpc.GrpcService;

public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        final Server backendServer = startBackendServer();

        final Server reverseProxyServer = startReverseProxyServer();

        backendServer.start().join();
        reverseProxyServer.start().join();

        final WebClient client = WebClient.of("http://localhost:9090");

        final CompletableFuture<AggregatedHttpResponse> responseFuture = client.get("/hello/John")
                .aggregate();

        responseFuture.thenAccept(response -> {
            final String responseBody = response.contentUtf8();
            logger.info("Response from proxy server: " + responseBody);
        }).exceptionally(throwable -> {
            logger.error("Failed to receive response from proxy server: " + throwable.getMessage());
            return null;
        }).thenRun(() -> {
            backendServer.stop();
            reverseProxyServer.stop();
        });
    }

    private static Server startBackendServer() {
        return Server.builder()
                .http(8080)
                .service(GrpcService.builder()
                        .addService(new HelloService())
                        .build())
                .build();
    }

    private static Server startReverseProxyServer() {
        return Server.builder()
                .http(9090)
                .annotatedService(new ReverseProxyService())
                .build();
    }

    public static class ReverseProxyService {
        private final WebClient backendClient;

        public ReverseProxyService() {
            backendClient = WebClient.builder("http://localhost:8080").build();
        }

        @Get("/hello/{name}")
        @ProducesJson
        @LoggingDecorator
        public HttpResponse proxyHello(@Param("name") String name) {
            final HelloServiceGrpc.HelloServiceBlockingStub stub = GrpcClients.builder(backendClient.uri())
                    .build(HelloServiceGrpc.HelloServiceBlockingStub.class);
            final Hello.HelloReply reply = stub.hello(Hello.HelloRequest.newBuilder().setName(name).build());
            return HttpResponse.ofJson(reply.getMessage());
        }
    }
}
