package example.armeria.grpc;

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceWithRoutes;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.grpc.GrpcService;

import example.armeria.grpc.Hello.HelloRequest;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;

public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final Server server = newServer(8080, 8443);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop().join();
            logger.info("Server has been stopped.");
        }));

        server.start().join();
        final InetSocketAddress localAddress = server.activePort().get().localAddress();
        final boolean isLocalAddress = localAddress.getAddress().isAnyLocalAddress() ||
                                       localAddress.getAddress().isLoopbackAddress();
        logger.info("Server has been started. Serving DocService at http://{}:{}/docs",
                    isLocalAddress ? "127.0.0.1" : localAddress.getHostString(), localAddress.getPort());
    }

    static Server newServer(int httpPort, int httpsPort) throws Exception {
        final HelloRequest exampleRequest = HelloRequest.newBuilder().setName("Armeria").build();
        final ServiceWithRoutes<HttpRequest, HttpResponse> grpcService =
                GrpcService.builder()
                           .addService(new HelloServiceImpl())
                           // See https://github.com/grpc/grpc-java/blob/master/documentation/server-reflection-tutorial.md
                           .addService(ProtoReflectionService.newInstance())
                           .supportedSerializationFormats(GrpcSerializationFormats.values())
                           .enableUnframedRequests(true)
                           // You can set useBlockingTaskExecutor(true) in order to execute all gRPC
                           // methods in the blockingTaskExecutor thread pool.
                           // .useBlockingTaskExecutor(true)
                           .build();
        return Server.builder()
                     .http(httpPort)
                     .https(httpsPort)
                     .tlsSelfSigned()
                     .service(grpcService)
                    // You can access the documentation service at http://127.0.0.1:8080/docs.
                    // See https://line.github.io/armeria/server-docservice.html for more information.
                    .serviceUnder("/docs", new DocServiceBuilder()
                        .exampleRequestForMethod(HelloServiceGrpc.SERVICE_NAME,
                                                 "Hello", exampleRequest)
                        .exampleRequestForMethod(HelloServiceGrpc.SERVICE_NAME,
                                                 "LazyHello", exampleRequest)
                        .exampleRequestForMethod(HelloServiceGrpc.SERVICE_NAME,
                                                 "BlockingHello", exampleRequest)
                        .exclude(DocServiceFilter.ofServiceName(ServerReflectionGrpc.SERVICE_NAME))
                        .build())
                     .build();
    }

    private Main() {}
}
