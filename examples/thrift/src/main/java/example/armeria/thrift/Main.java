package example.armeria.thrift;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.thrift.THttpService;

public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final Server server = newServer(8080, 8443);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop().join();
            logger.info("Server has been stopped.");
        }));

        server.start().join();

        logger.info("Server has been started. Serving DocService at http://127.0.0.1:{}/docs",
                    server.activeLocalPort());
    }

    static Server newServer(int httpPort, int httpsPort) throws Exception {
        final HelloRequest exampleRequest = new HelloRequest("Armeria");
        final THttpService thriftService =
                THttpService.builder()
                            .addService(new HelloServiceImpl())
                            .build();
        return Server.builder()
                     .http(httpPort)
                     .https(httpsPort)
                     .tlsSelfSigned()
                     .service("/", thriftService)
                     // You can access the documentation service at http://127.0.0.1:8080/docs.
                     // See https://armeria.dev/docs/server-docservice for more information.
                     .serviceUnder("/docs",
                                   DocService.builder()
                                             .exampleRequests(HelloService.class, "hello", exampleRequest)
                                             .exampleRequests(HelloService.class, "lazyHello", exampleRequest)
                                             .exampleRequests(HelloService.class, "blockingHello",
                                                              exampleRequest)
                                             .build())
                     .build();
    }

    private Main() {}
}
