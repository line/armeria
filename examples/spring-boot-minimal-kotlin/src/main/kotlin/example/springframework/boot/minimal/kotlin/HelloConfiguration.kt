package example.springframework.boot.minimal.kotlin

import com.linecorp.armeria.server.docs.DocService
import com.linecorp.armeria.server.logging.AccessLogWriter
import com.linecorp.armeria.server.logging.LoggingService
import com.linecorp.armeria.spring.ArmeriaServerConfigurator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * An example of a configuration which provides beans for customizing the server and client.
 */
@Configuration
class HelloConfiguration {
    /**
     * A user can configure a [Server] by providing an [ArmeriaServerConfigurator] bean.
     */
    @Bean
    fun armeriaServerConfigurator(service: HelloAnnotatedService): ArmeriaServerConfigurator =
        // Customize the server using the given ServerBuilder. For example:
        ArmeriaServerConfigurator {
            // Add DocService that enables you to send Thrift and gRPC requests from web browser.
            it.serviceUnder("/docs", DocService())

            // Log every message which the server receives and responds.
            it.decorator(LoggingService.newDecorator())

            // Write access log after completing a request.
            it.accessLogWriter(AccessLogWriter.combined(), false)

            // Add an Armeria annotated HTTP service.
            it.annotatedService(service)

            // You can also bind asynchronous RPC services such as Thrift and gRPC:
            // it.service(THttpService.of(...));
            // it.service(GrpcService.builder()...build());
        }
}
