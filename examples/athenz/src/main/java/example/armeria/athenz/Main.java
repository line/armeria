package example.armeria.athenz;

import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;

import java.io.File;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.yahoo.athenz.zms.ZMSClient;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.athenz.AthenzDocker;
import com.linecorp.armeria.server.athenz.AthenzPolicyConfig;
import com.linecorp.armeria.server.athenz.AthenzServiceDecoratorFactory;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.grpc.GrpcService;

import example.armeria.grpc.Hello.HelloRequest;
import example.armeria.grpc.HelloServiceGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;

public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        // Assuming you have Docker installed and running, this example will start a
        // ZTS (ZMS and ZTS) server using Docker Compose.
        final AthenzDocker athenzDocker = newAthenzDocker();
        athenzDocker.initialize();
        final Server server = newServer(8080, 8443, athenzDocker.ztsUri());

        server.closeOnJvmShutdown(athenzDocker::close);

        server.start().join();

        logger.info("Server has been started. Serving DocService at http://127.0.0.1:{}/docs",
                    server.activeLocalPort());
    }

    private static Server newServer(int httpPort, int httpsPort, URI ztsUri) throws Exception {
        final ServerBuilder sb = Server.builder();
        sb.http(httpPort)
          .https(httpsPort)
          .tlsSelfSigned();
        configureServices(sb);
        configureAthenz(sb, ztsUri);
        return sb.build();
    }

    static AthenzDocker newAthenzDocker() {
        return new AthenzDocker(new File("gen-src/main/resources/docker/docker-compose.yml")) {
            @Override
            protected void scaffold(ZMSClient zmsClient) {
                createRole("test_role",
                           ImmutableList.of(TEST_DOMAIN_NAME + '.' + TEST_SERVICE,
                                            TEST_DOMAIN_NAME + '.' + FOO_SERVICE));
                createPolicy("greeting-policy", "test_role", "greeting", "hello");
            }
        };
    }

    static void configureAthenz(ServerBuilder sb, URI ztsUri) {
        final String providerKeyFile = "gen-src/main/resources/docker/certs/test-service/key.pem";
        final String providerCertFile = "gen-src/main/resources/docker/certs/test-service/cert.pem";
        final String caCertFile = "gen-src/main/resources/docker/certs/CAs/athenz_ca_cert.pem";

        final ZtsBaseClient ztsBaseClient =
                ZtsBaseClient.builder(ztsUri)
                             .keyPair(providerKeyFile, providerCertFile)
                             // caCertFile may not be necessary in production, but it is required for testing.
                             .trustedCertificate(caCertFile)
                             .build();

        final AthenzServiceDecoratorFactory decoratorFactory = AthenzServiceDecoratorFactory
                .builder(ztsBaseClient)
                .policyConfig(new AthenzPolicyConfig(TEST_DOMAIN_NAME))
                .build();

        final DependencyInjector dependencyInjector =
                DependencyInjector.ofSingletons(decoratorFactory)
                                  .orElse(DependencyInjector.ofReflective());
        sb.dependencyInjector(dependencyInjector, false);
        sb.serverListener(new ServerListenerAdapter() {
            @Override
            public void serverStopped(Server server) {
                ztsBaseClient.close();
            }
        });
    }

    static void configureServices(ServerBuilder sb) {
        final HelloRequest exampleRequest = HelloRequest.newBuilder().setName("Armeria").build();
        final GrpcService grpcService =
                GrpcService.builder()
                           .addService(new GrpcServiceImpl())
                           .enableUnframedRequests(true)
                           .build();
        sb.service("prefix:/grpc", grpcService)
          .annotatedService("/rest", new RestServiceImpl())

          // You can access the documentation service at http://127.0.0.1:8080/docs.
          // See https://line.github.io/armeria/docs/server-docservice for more information.
          .serviceUnder("/docs",
                        DocService.builder()
                                  .exampleRequests(HelloServiceGrpc.SERVICE_NAME,
                                                   "Hello", exampleRequest)
                                  .exclude(DocServiceFilter.ofServiceName(
                                          ServerReflectionGrpc.SERVICE_NAME))
                                  .build());
    }

    private Main() {}
}
