package example.armeria.reverseproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.linecorp.armeria.common.SessionProtocol;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.internal.common.util.PortUtil;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;

import example.armeria.reverseproxy.Hello.HelloReply;
import example.armeria.reverseproxy.Hello.HelloRequest;

class GrpcReverseProxyServerTest {

    private static final Logger logger = LoggerFactory.getLogger(GrpcReverseProxyServerTest.class);

    private static Server server;

    private static final int envoyPort = PortUtil.unusedTcpPort();

    @Container
    private static GenericContainer<?> envoy;

    @BeforeAll
    static void startServer() {
        server = Server.builder()
                .http(0)
                .service(GrpcService.builder()
                        .addService(new HelloService())
                        .build())
                .build();

        server.start().join();
        final int serverPort = server.activeLocalPort(SessionProtocol.HTTP);

        try {
            final Path source = Paths.get("./envoy/envoy.yaml");
            final Path destination = Paths.get("./envoy/envoy_backup.yaml");
            Files.copy(source, destination);
            String envoyConfig = new String(Files.readAllBytes(source));
            envoyConfig = envoyConfig.replace("${ENVOY_PORT}", String.valueOf(envoyPort))
                    .replace("${SERVER_PORT}", String.valueOf(serverPort));
            Files.write(source, envoyConfig.getBytes());
            envoy = new GenericContainer<>("envoyproxy/envoy:v1.22.0")
                    .withExposedPorts(envoyPort)
                    .withFileSystemBind(source.toString(), "/etc/envoy/envoy.yaml", BindMode.READ_WRITE)
                    .withStartupTimeout(Duration.ofSeconds(60));
        } catch (IOException ex) {
            logger.error("Failed to configure Envoy container", ex);
        }
    }

    @AfterAll
    static void stopServer() {
        try {
            final Path source = Paths.get("./envoy/envoy_backup.yaml");
            final Path destination = Paths.get("./envoy/envoy.yaml");
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            logger.error("Failed to restore Envoy configuration", ex);
        }
        server.stop().join();
    }

    @Test
    void reverseProxy() {

        try {
            // given
            final CompletableFuture<Void> envoyStartFuture = CompletableFuture.runAsync(() -> envoy.start());

            envoyStartFuture.thenRun(() -> {
                final String envoyAddress = "http://" + envoy.getHost() + ":" + envoy.getMappedPort(envoyPort);

                final WebClient client = WebClient.of(envoyAddress);
                final HelloServiceGrpc.HelloServiceBlockingStub helloService = GrpcClients.builder(client.uri())
                        .build(HelloServiceGrpc.HelloServiceBlockingStub.class);

                // when
                final var helloRequest = HelloRequest.newBuilder().setName("Armeria").build();
                final HelloReply reply = helloService.hello(helloRequest);

                // then
                assertThat(reply.getMessage()).isEqualTo("Hello, Armeria!");
                envoy.stop();
            });
            envoyStartFuture.join();
        } catch (CompletionException ex) {
            logger.error("Could not find a valid Docker environment", ex);
        }
    }
}
