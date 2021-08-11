package example.armeria.server.files;

import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.file.FileService;
import com.linecorp.armeria.server.file.HttpFile;

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

    private static Server newServer(int httpPort, int httpsPort) throws Exception {
        final ServerBuilder sb = Server.builder();
        sb.http(httpPort)
          .https(httpsPort)
          .tlsSelfSigned();
        configureServices(sb);
        return sb.build();
    }

    static void configureServices(ServerBuilder sb) {
        // Serve an individual file.
        sb.service("/favicon.ico",
                   HttpFile.of(Main.class.getClassLoader(), "favicon.ico")
                           .asService())
          // Serve the files under the current user's home directory.
          .service("prefix:/",
                   FileService.builder(Paths.get(System.getProperty("user.home")))
                              .autoIndex(true)
                              .build())
          .build();
    }

    private Main() {}
}
