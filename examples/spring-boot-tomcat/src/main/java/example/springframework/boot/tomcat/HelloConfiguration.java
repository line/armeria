package example.springframework.boot.tomcat;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.server.tomcat.TomcatService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

/**
 * Configures an Armeria {@link Server} to redirect the incoming requests to the Tomcat instance provided by
 * Spring Boot. It also sets up a {@link HealthChecker} so that it works well with a load balancer.
 */
@Configuration
public class HelloConfiguration {

    /**
     * Extracts a Tomcat {@link Connector} from Spring webapp context.
     */
    public static Connector getConnector(ServletWebServerApplicationContext applicationContext) {
        final TomcatWebServer container = (TomcatWebServer) applicationContext.getWebServer();

        // Start the container to make sure all connectors are available.
        container.start();
        return container.getTomcat().getConnector();
    }

    /**
     * Returns a new {@link HealthChecker} that marks the server as unhealthy when Tomcat becomes unavailable.
     */
    @Bean
    public HealthChecker tomcatConnectorHealthChecker(ServletWebServerApplicationContext applicationContext) {
        final Connector connector = getConnector(applicationContext);
        return () -> connector.getState().isAvailable();
    }

    /**
     * Returns a new {@link TomcatService} that redirects the incoming requests to the Tomcat instance
     * provided by Spring Boot.
     */
    @Bean
    public TomcatService tomcatService(ServletWebServerApplicationContext applicationContext) {
        return TomcatService.forConnector(getConnector(applicationContext));
    }

    /**
     * Returns a new {@link ArmeriaServerConfigurator} that is responsible for configuring a {@link Server}
     * using the given {@link ServerBuilder}.
     */
    @Bean
    public ArmeriaServerConfigurator armeriaServiceInitializer(TomcatService tomcatService) {
        return sb -> sb.service("prefix:/", tomcatService);
    }
}
