package example.springframework.boot.armeria;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import com.linecorp.armeria.spring.web.reactive.ArmeriaClientConfigurator;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * An example of a configuration which provides beans for customizing the server and client.
 */
@Configuration
public class HelloConfiguration {

    /**
     * A user can configure a {@link Server} by providing an {@link ArmeriaServerConfigurator} bean.
     */
    @Bean
    public ArmeriaServerConfigurator armeriaServerConfigurator() {
        // An example which adds a LoggingService decorator to the ServerBuilder in order to log every request.
        return builder -> builder.decorator(LoggingService.newDecorator())
                                 .accessLogWriter(AccessLogWriter.combined(), false);
    }

    /**
     * A user can configure a {@link Client} by providing an {@link ArmeriaClientConfigurator} bean.
     */
    @Bean
    public ArmeriaClientConfigurator armeriaClientConfigurator() {
        // An example which sets a custom client factory to the HttpClientBuilder in order not to validate
        // a certificate received from the server.
        return builder -> builder.factory(
                new ClientFactoryBuilder()
                        .sslContextCustomizer(b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE))
                        .build());
    }
}
