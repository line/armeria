package example.armeria.proxy;

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.file.HttpFileBuilder;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.server.logging.LoggingService;

public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final Server backend1 = newBackendServer(8081, 500);
        final Server backend2 = newBackendServer(8082, 250);
        final Server backend3 = newBackendServer(8083, 100);
        // You can also remove the delay between frames completely as:
        //
        //   final Server backend = new BackendServer(8083, 0);
        //
        // The proxy server will handle backpressure perfectly fine
        // even if your browser cannot receive fast enough.

        backend1.start().join();
        backend2.start().join();
        backend3.start().join();

        final Server proxyServer = newProxyServer(8080, 8443);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            backend1.stop().join();
            backend2.stop().join();
            backend3.stop().join();

            proxyServer.stop().join();
            logger.info("The proxy server has been stopped.");
        }));

        proxyServer.start().join();

        final InetSocketAddress localAddress = proxyServer.activePort().get().localAddress();
        final boolean isLocalAddress = localAddress.getAddress().isAnyLocalAddress() ||
                                       localAddress.getAddress().isLoopbackAddress();
        logger.info("The proxy server has been started. Connect at http://{}:{}/",
                    isLocalAddress ? "127.0.0.1" : localAddress.getHostString(), localAddress.getPort());
    }

    static Server newBackendServer(int port, int frameIntervalMillis) throws Exception {
        return Server.builder()
                     .http(port)
                     // Disable timeout to serve infinite streaming response.
                     .requestTimeoutMillis(0)
                     // Serve /index.html file.
                     .service("/", HttpFileBuilder.ofResource(Main.class.getClassLoader(), "index.html")
                                                 .cacheControl(ServerCacheControl.REVALIDATED)
                                                 .build()
                                                 .asService())
                     .service("/animation", new AnimationService(frameIntervalMillis))
                     // Serve health check.
                     .service("/internal/l7check", new HttpHealthCheckService())
                     .build();
    }

    static Server newProxyServer(int httpPort, int httpsPort) throws Exception {
        return Server.builder()
                     .http(httpPort)
                     .https(httpsPort)
                     .tlsSelfSigned()
                     // Disable timeout to serve infinite streaming response.
                     .requestTimeoutMillis(0)
                     .serviceUnder("/", new ProxyService())
                     .decorator(LoggingService.newDecorator())
                     .build();
    }

    private Main() {}
}
