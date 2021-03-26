package example.springframework.boot.minimal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

/**
 * An example of a configuration which provides beans for customizing the server and client.
 */
@Configuration
public class HelloConfiguration {

    /**
     * A user can configure a {@link Server} by providing an {@link ArmeriaServerConfigurator} bean.
     */
    @Bean
    public ArmeriaServerConfigurator armeriaServerConfigurator(HelloAnnotatedService service) {
        // Customize the server using the given ServerBuilder. For example:
        return builder -> {
            // Add DocService that enables you to send Thrift and gRPC requests from web browser.
            builder.serviceUnder("/docs", new DocService());

            // Log every message which the server receives and responds.
            builder.decorator(LoggingService.newDecorator());

            // Write access log after completing a request.
            builder.accessLogWriter(AccessLogWriter.combined(), false);

            // Add an Armeria annotated HTTP service.
            builder.annotatedService(service);

            // Add an example MetricCollectingService decorator
            builder.decorator(MetricCollectingService
                                      .builder()
                                      .successFunction(log -> {
                                          final int statusCode = log.responseHeaders().status().code();
                                          return (statusCode >= 200 && statusCode < 400) || statusCode == 404;
                                      })
                                      .newDecorator(MeterIdPrefixFunction.ofDefault("hello")));

            // You can also bind asynchronous RPC services such as Thrift and gRPC:
            // builder.service(THttpService.of(...));
            // builder.service(GrpcService.builder()...build());
        };
    }
}
