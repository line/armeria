package example.springframework.boot.webflux;

import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerHttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategy;
import com.linecorp.armeria.client.retry.RetryStrategy;
import com.linecorp.armeria.client.retry.RetryingHttpClient;
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

            // Logging every request and response which is received by the server.
            builder.decorator(LoggingService.newDecorator());

            // Write access log after completing a request.
            builder.accessLogWriter(AccessLogWriter.combined(), false);

            // You can also bind asynchronous RPC services such as Thrift and gRPC:
            // builder.service(THttpService.of(...));
            // builder.service(new GrpcServiceBuilder()...build());
        };
    }

    /**
     * A user can configure a {@link Client} by providing an {@link ArmeriaClientConfigurator} bean.
     */
    @Bean
    public ArmeriaClientConfigurator armeriaClientConfigurator() {
        // Customize the client using the given HttpClientBuilder. For example:
        return builder -> {
            // Use circuit breaker for every endpoint.
            final Function<String, CircuitBreaker> factory = key -> CircuitBreaker.of("my-cb-" + key);
            final CircuitBreakerStrategy strategy = CircuitBreakerStrategy.onServerErrorStatus();
            builder.decorator(CircuitBreakerHttpClient.newPerHostDecorator(factory, strategy));

            // Automatically retry a request when the server returns a 5xx response.
            builder.decorator(RetryingHttpClient.newDecorator(RetryStrategy.onServerErrorStatus()));

            // Use a custom client factory in order not to validate a certificate received from the server.
            final ClientFactory clientFactory = new ClientFactoryBuilder().sslContextCustomizer(
                    b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE)).build();
            builder.factory(clientFactory);
        };
    }
}
