package example.armeria.server.annotated;

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
        final ServerBuilder sb = new ServerBuilder();

        return sb.http(port)
                 .annotatedService("/pathPattern", new PathPatternService())
                 .annotatedService("/injection", new InjectionService())
                 .annotatedService("/messageConverter", new MessageConverterService())
                 .annotatedService("/exception", new ExceptionHandlerService())
                 .annotatedService("/sse", new ServerSentEventsService())
                 .build();
    }

    private ServerFactory() {}
}
