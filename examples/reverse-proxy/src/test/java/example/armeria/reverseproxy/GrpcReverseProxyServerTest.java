package example.armeria.reverseproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

    private static Server server;

    private static final int serverPort = PortUtil.unusedTcpPort();

    private static final int envoyPort = PortUtil.unusedTcpPort();

    @Container
    private static GenericContainer<?> envoy;

    static {
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
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @BeforeAll
    static void startServer() {
        server = Server.builder()
                .http(serverPort)
                .service(GrpcService.builder()
                        .addService(new HelloService())
                        .build())
                .build();
        server.start().join();
        envoy.start();
    }

    @AfterAll
    static void stopServer() {
        try {
            final Path source = Paths.get("./envoy/envoy_backup.yaml");
            final Path destination = Paths.get("./envoy/envoy.yaml");
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
        server.stop().join();
        envoy.stop();
    }

    @Test
    void reverseProxy() {

        // given
        final String envoyAddress = "http://" + envoy.getHost() + ":" + envoy.getMappedPort(envoyPort);

        final WebClient client = WebClient.of(envoyAddress);
        final HelloServiceGrpc.HelloServiceBlockingStub helloService = GrpcClients.builder(client.uri())
                .build(HelloServiceGrpc.HelloServiceBlockingStub.class);

        // when
        final HelloReply reply = helloService.hello(HelloRequest.newBuilder().setName("Armeria").build());

        // then
        assertThat(reply.getMessage()).isEqualTo("Hello, Armeria!");
    }
}
