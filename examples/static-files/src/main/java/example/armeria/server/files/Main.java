package example.armeria.server.files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.file.HttpFileServiceBuilder;

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
                // Serve an individual file.
                .service("/favicon.ico", HttpFile.ofResource(Main.class.getClassLoader(), "favicon.ico")
                                                 .asService())
                // Serve the files under the current user's home directory.
                .service("prefix:/", HttpFileServiceBuilder.forFileSystem(System.getProperty("user.home"))
                                                           .autoIndex(true)
                                                           .build())
                .build();
    }

    private Main() {}
}
