package example.armeria.server.blog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;

public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final Server server = newServer(8080);

        server.closeOnShutdown();

        server.start().join();

        logger.info("Server has been started. Serving DocService at http://127.0.0.1:{}/docs",
                    server.activeLocalPort());
    }

    /**
     * Returns a new {@link Server} instance which serves the blog service.
     *
     * @param port the port that the server is to be bound to
     */
    static Server newServer(int port) {
        final ServerBuilder sb = Server.builder();
        final DocService docService =
                DocService.builder()
                          .exampleRequests(BlogService.class,
                                           "createBlogPost",
                                           "{\"title\":\"My first blog\", \"content\":\"Hello Armeria!\"}")
                          .build();
        return sb.http(port)
                 .annotatedService(new BlogService())
                 .serviceUnder("/docs", docService)
                 .build();
    }
}
