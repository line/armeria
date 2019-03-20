package example.armeria.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

import io.grpc.protobuf.services.ProtoReflectionService;

public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final Server server = newServer(8080, 8443);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop().join();
            logger.info("Server has been stopped.");
        }));

        server.start().join();
        logger.info("Server has been started.");
    }

    static Server newServer(int httpPort, int httpsPort) throws Exception {
        return new ServerBuilder()
                .http(httpPort)
                .https(httpsPort)
                .tlsSelfSigned()
                .service(new GrpcServiceBuilder()
                                 .addService(new HelloServiceImpl())
                                 // See https://github.com/grpc/grpc-java/blob/master/documentation/server-reflection-tutorial.md
                                 .addService(ProtoReflectionService.newInstance())
                                 .supportedSerializationFormats(GrpcSerializationFormats.values())
                                 .enableUnframedRequests(true)
                                 .build())
                // You can access the documentation service at http://127.0.0.1:8080/docs.
                // See https://line.github.io/armeria/server-docservice.html for more information.
                .serviceUnder("/docs", new DocService())
                .build();
    }

    private Main() {}
}
