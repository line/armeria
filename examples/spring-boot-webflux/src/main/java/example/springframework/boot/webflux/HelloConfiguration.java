package example.springframework.boot.webflux;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerHttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategy;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.docs.DocService;
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
        // Customize the server using the given ServerBuilder. For example:
        return builder -> {
            // Add DocService that enables you to send Thrift and gRPC requests from web browser.
            builder.serviceUnder("/docs", new DocService());

            // Log every message which the server receives and responds.
            builder.decorator(LoggingService.newDecorator());

            // Write access log after completing a request.
            builder.accessLogWriter(AccessLogWriter.combined(), false);

            // You can also bind annotated HTTP services and asynchronous RPC services such as Thrift and gRPC:
            // builder.annotatedService("/rest", service);
            // builder.service("/thrift", THttpService.of(...));
            // builder.service(GrpcService.builder()...build());
        };
    }

    /**
     * Returns a custom {@link ClientFactory} with TLS certificate validation disabled,
     * which means any certificate received from the server will be accepted without any verification.
     * It is used for an example which makes the client send an HTTPS request to the server running
     * on localhost with a self-signed certificate. Do NOT use the {@link InsecureTrustManagerFactory}
     * in production.
     */
    @Bean
    public ClientFactory clientFactory() {
        return new ClientFactoryBuilder().sslContextCustomizer(
                b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE)).build();
    }

    /**
     * A user can configure a {@link Client} by providing an {@link ArmeriaClientConfigurator} bean.
     */
    @Bean
    public ArmeriaClientConfigurator armeriaClientConfigurator(ClientFactory clientFactory) {
        // Customize the client using the given HttpClientBuilder. For example:
        return builder -> {
            // Use a circuit breaker for each remote host.
            final CircuitBreakerStrategy strategy = CircuitBreakerStrategy.onServerErrorStatus();
            builder.decorator(CircuitBreakerHttpClient.builder(strategy)
                                                      .newDecorator());

            // Set a custom client factory.
            builder.factory(clientFactory);
        };
    }
}
