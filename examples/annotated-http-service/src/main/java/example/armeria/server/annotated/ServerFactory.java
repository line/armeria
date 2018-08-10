package example.armeria.server.annotated;

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * A factory class which creates an Armeria {@link Server} instance.
 */
public final class ServerFactory {
    /**
     * Returns a new {@link Server} instance configured with annotated HTTP services.
     *
     * @param port the port that the server is to be bound to
     */
    public static Server of(int port) {
        checkArgument(port >= 0 && port <= 65535, "port: %s (expected: 0-65535)");
        final ServerBuilder sb = new ServerBuilder();

        return sb.port(port, SessionProtocol.HTTP)
                 .annotatedService("/pathPattern", new PathPatternService())
                 .annotatedService("/injection", new InjectionService())
                 .annotatedService("/messageConverter", new MessageConverterService())
                 .annotatedService("/exception", new ExceptionHandlerService())
                 .build();
    }

    private ServerFactory() {}
}
