package example.springframework.boot.minimal

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.docs.DocService
import com.linecorp.armeria.server.logging.AccessLogWriter
import com.linecorp.armeria.server.logging.LoggingService
import com.linecorp.armeria.spring.ArmeriaServerConfigurator

/**
 * An example of a configuration which provides beans for customizing the server and client.
 */
@Configuration
open class HelloConfiguration {

    /**
     * A user can configure a [Server] by providing an [ArmeriaServerConfigurator] bean.
     */
    @Bean
    open fun armeriaServerConfigurator(service: HelloAnnotatedService): ArmeriaServerConfigurator {
        // Customize the server using the given ServerBuilder. For example:
        return ArmeriaServerConfigurator { builder ->
            // Add DocService that enables you to send Thrift and gRPC requests from web browser.
            builder.serviceUnder("/docs", DocService())

            // Log every message which the server receives and responds.
            builder.decorator(LoggingService.newDecorator<HttpRequest, HttpResponse>())

            // Write access log after completing a request.
            builder.accessLogWriter(AccessLogWriter.combined(), false)

            // Add an Armeria annotated HTTP service.
            builder.annotatedService(service)

            // You can also bind asynchronous RPC services such as Thrift and gRPC:
            // builder.service(THttpService.of(...));
            // builder.service(new GrpcServiceBuilder()...build());
        }
    }
}
