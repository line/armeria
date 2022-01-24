package example.armeria.server.graphql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.graphql.GraphqlService;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        final Server server = newServer(8080);

        server.closeOnShutdown();

        server.start().join();
    }

    /**
     * Returns a new {@link Server} instance configured with GraphQL HTTP services.
     *
     * @param port the port that the server is to be bound to
     */
    private static Server newServer(int port) {
        final ServerBuilder sb = Server.builder();
        sb.http(port);
        configureService(sb);
        return sb.build();
    }

    static void configureService(ServerBuilder sb) {
        sb.service("/graphql", GraphqlService.builder().runtimeWiring(c -> {
            c.type("Query",
                   typeWiring -> typeWiring.dataFetcher("user", new UserDataFetcher()));
        }).build());

        // Browsing and invoking GraphQL services using GraphQL Playground.
        sb.service("/graphql/playground", HttpFile.of(Main.class.getClassLoader(), "/graphql-playground.html")
                                                  .asService());
    }
}
