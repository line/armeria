package example.armeria.grpc.envoy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

import com.linecorp.armeria.common.util.ShutdownHooks;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;

public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final int serverPort = 8080;
    // the port envoy binds to within the container
    private static final int envoyPort = 10000;

    public static void main(String[] args) {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new IllegalStateException("Docker is not available");
        }

        final Server backendServer = startBackendServer(serverPort);
        backendServer.closeOnJvmShutdown();
        backendServer.start().join();
        logger.info("Serving backend at http://127.0.0.1:{}/", backendServer.activePort());

        final EnvoyContainer envoyProxy = configureEnvoy(serverPort, envoyPort);
        ShutdownHooks.addClosingTask(envoyProxy::stop);
        envoyProxy.start();
        final Integer mappedEnvoyPort = envoyProxy.getMappedPort(envoyPort);
        logger.info("Serving envoy at http://127.0.0.1:{}/", mappedEnvoyPort);
    }

    private static Server startBackendServer(int serverPort) {
        return Server.builder()
                     .http(serverPort)
                     .service(GrpcService.builder()
                                         .addService(new HelloService())
                                         .build())
                     .build();
    }

    static EnvoyContainer configureEnvoy(int serverPort, int envoyPort) {
        final String sedPattern = String.format("s/SERVER_PORT/%s/g;s/ENVOY_PORT/%s/g", serverPort, envoyPort);
        return new EnvoyContainer("envoy/envoy.yaml", sedPattern)
                .withExposedPorts(envoyPort);
    }

    private Main() {}
}
